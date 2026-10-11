package eu.teele.neugesplitter.ui.join

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.probe.Offer
import eu.teele.neugesplitter.sync.probe.Verdict
import eu.teele.neugesplitter.sync.probe.offer
import eu.teele.neugesplitter.sync.probe.probe
import eu.teele.neugesplitter.ui.backend.Location
import eu.teele.neugesplitter.ui.backend.pasted
import eu.teele.neugesplitter.work.Syncing
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class JoinStep {
    LOCATION,
    TEST,
    CONTENTS,
    PASSPHRASE,
    FETCH,
    WHO,
}

/** What a location holds that is no group to join (BE-9). */
enum class Found {
    EMPTY,
    FOREIGN,
    /** A group this installation holds already. */
    HELD,
    /** A group whose history cannot be read or applied. */
    UNREADABLE,
}

/**
 * Where the join flow stands (ui-design 3).
 *
 * @property found why the location offers nothing to join; null while it might.
 * @property held the group the location holds, when this installation holds it already.
 * @property wrong the passphrase typed does not open the group.
 * @property failure why the last attempt to reach the backend failed.
 * @property group the group fetched, to choose who to act as in.
 * @property chosen the member chosen; null for a new one, named [name].
 * @property rejected the new member's name is past the limits every reader holds to.
 * @property done the group joined, or held already and asked for.
 */
data class JoinState(
    val step: JoinStep = JoinStep.LOCATION,
    val location: Location = Location(),
    val invalid: Boolean = false,
    val busy: Boolean = false,
    val verdict: Verdict? = null,
    val found: Found? = null,
    val held: GroupId? = null,
    val passphrase: String = "",
    val wrong: Boolean = false,
    val failure: StoreException? = null,
    val group: Group? = null,
    val chosen: MemberId? = null,
    val adding: Boolean = false,
    val name: String = "",
    val rejected: Boolean = false,
    val done: GroupId? = null,
) {
    val readOnly: Boolean
        get() = verdict == Verdict.ReadOnly

    /** Who the user can be: every member not removed (BE-6). */
    val members: List<Member>
        get() = group?.let(::candidates).orEmpty()

    val canContinue: Boolean
        get() =
            !busy &&
                when (step) {
                    JoinStep.LOCATION -> location.complete
                    JoinStep.TEST -> verdict == Verdict.Accepted || verdict == Verdict.ReadOnly
                    JoinStep.CONTENTS -> false
                    JoinStep.PASSPHRASE -> passphrase.isNotEmpty()
                    JoinStep.FETCH -> failure != null
                    JoinStep.WHO -> if (adding) name.isNotBlank() else chosen != null
                }

    override fun toString(): String = "JoinState($step, busy=$busy, done=$done)"
}

/**
 * Joins a group shared elsewhere: checks its location, unlocks it if it is encrypted, fetches its
 * history and asks who the user is in it (ui-design 3, BE-5, BE-6, BE-9, BE-11).
 */
class JoinViewModel(
    private val groups: GroupRepository,
    /** The backend at a location, given its secret key; throws for an unusable address. */
    private val stores: (BackendSettings, String) -> LogStore,
    private val syncing: Syncing,
    private val identifier: () -> String,
    private val now: () -> Instant,
) : ViewModel() {

    private val mutable = MutableStateFlow(JoinState())
    val state: StateFlow<JoinState> = mutable.asStateFlow()

    /** The backend as tested, and what it held then. */
    private var store: LogStore? = null
    private var offer: Offer? = null
    /** The group being fetched or fetched, until it is joined or given up on. */
    private var fetched: GroupId? = null
    /** Testing, unlocking or fetching, which going back stops. */
    private var work: Job? = null

    fun location(location: Location) =
        mutable.update { it.copy(location = location, invalid = false) }

    /** Fills every field [text] names; false if it names none. */
    fun paste(text: String): Boolean {
        val found = pasted(text)
        if (found.empty) return false
        mutable.update { it.copy(location = it.location.with(found), invalid = false) }
        return true
    }

    fun passphrase(text: String) = mutable.update { it.copy(passphrase = text, wrong = false) }

    fun choose(member: MemberId) = mutable.update { it.copy(chosen = member, adding = false) }

    fun adding() = mutable.update { it.copy(chosen = null, adding = true) }

    fun name(text: String) = mutable.update { it.copy(name = text, rejected = false) }

    /** Opens the group the location holds, which this installation holds already. */
    fun open() = mutable.update { it.copy(done = it.held) }

    fun next() {
        val state = mutable.value
        if (!state.canContinue) return
        when (state.step) {
            JoinStep.LOCATION -> test(state.location)
            JoinStep.TEST -> found(checkNotNull(offer))
            JoinStep.CONTENTS -> Unit
            JoinStep.PASSPHRASE -> unlock(state.passphrase)
            JoinStep.FETCH -> fetch(checkNotNull(fetched), (offer as? Offer.Locked)?.root)
            JoinStep.WHO -> join(state)
        }
    }

    /** Goes back one step; false if this is the first, and the flow should close. */
    fun back(): Boolean {
        val state = mutable.value
        // A member chosen is being written, and the group is joined either way.
        if (state.step == JoinStep.WHO && state.busy) return true
        work?.cancel()
        val previous =
            when (state.step) {
                JoinStep.LOCATION -> return false
                JoinStep.TEST,
                JoinStep.CONTENTS -> JoinStep.LOCATION
                JoinStep.PASSPHRASE -> JoinStep.TEST
                JoinStep.FETCH,
                JoinStep.WHO -> {
                    abandon()
                    JoinStep.TEST
                }
            }
        mutable.update {
            it.copy(
                step = previous,
                busy = false,
                found = null,
                wrong = false,
                failure = null,
                group = null,
            )
        }
        return true
    }

    private fun test(location: Location) {
        val store =
            try {
                stores(location.settings, location.secretKey.trim())
            } catch (_: IllegalArgumentException) {
                mutable.update { it.copy(invalid = true) }
                return
            }
        this.store = store
        offer = null
        mutable.update {
            it.copy(step = JoinStep.TEST, busy = true, verdict = null, held = null, failure = null)
        }
        work =
            viewModelScope.launch {
                val verdict =
                    withContext(Dispatchers.IO) {
                        val verdict = probe(store)
                        if (verdict != Verdict.Accepted && verdict != Verdict.ReadOnly) {
                            return@withContext verdict
                        }
                        try {
                            offer = offer(store)
                            verdict
                        } catch (e: StoreException) {
                            Verdict.Unusable(e)
                        }
                    }
                mutable.update { it.copy(busy = false, verdict = verdict) }
            }
    }

    private fun found(offer: Offer) {
        when (offer) {
            Offer.Empty -> mutable.update { it.copy(step = JoinStep.CONTENTS, found = Found.EMPTY) }
            Offer.Foreign ->
                mutable.update { it.copy(step = JoinStep.CONTENTS, found = Found.FOREIGN) }
            is Offer.Open -> opened(offer.group, null)
            is Offer.Locked -> mutable.update { it.copy(step = JoinStep.PASSPHRASE) }
        }
    }

    private fun unlock(passphrase: String) {
        val root = (offer as Offer.Locked).root
        mutable.update { it.copy(busy = true, wrong = false, failure = null) }
        work =
            viewModelScope.launch {
                val chars = passphrase.toCharArray()
                try {
                    val id = syncing.unlock(checkNotNull(store), root, chars)
                    if (id == null) {
                        mutable.update {
                            it.copy(
                                busy = false,
                                step = JoinStep.CONTENTS,
                                found = Found.UNREADABLE,
                            )
                        }
                    } else {
                        mutable.update { it.copy(busy = false) }
                        opened(id, root)
                    }
                } catch (_: WrongPassphrase) {
                    mutable.update { it.copy(busy = false, wrong = true) }
                } catch (e: StoreException) {
                    mutable.update { it.copy(busy = false, failure = e) }
                } finally {
                    chars.fill('\u0000')
                }
            }
    }

    /** Group [id] is at the location: fetched, unless this installation holds it (BE-9). */
    private fun opened(id: GroupId, root: ByteArray?) {
        work =
            viewModelScope.launch {
                if (withContext(Dispatchers.IO) { groups.actingAs(id) } != null) {
                    mutable.update {
                        it.copy(step = JoinStep.CONTENTS, found = Found.HELD, held = id)
                    }
                } else {
                    fetch(id, root)
                }
            }
    }

    private fun fetch(id: GroupId, root: ByteArray?) {
        fetched = id
        mutable.update { it.copy(step = JoinStep.FETCH, busy = true, failure = null) }
        work =
            viewModelScope.launch {
                try {
                    val halted = syncing.fetch(id, checkNotNull(store), root)
                    if (halted != null) {
                        fetched = null
                        mutable.update {
                            it.copy(
                                busy = false,
                                step = JoinStep.CONTENTS,
                                found = Found.UNREADABLE,
                            )
                        }
                        return@launch
                    }
                    val group = withContext(Dispatchers.IO) { groups.load(id) }
                    mutable.update {
                        it.copy(
                            busy = false,
                            step = JoinStep.WHO,
                            group = group,
                            chosen = null,
                            adding = false,
                        )
                    }
                } catch (e: StoreException) {
                    mutable.update { it.copy(busy = false, failure = e) }
                }
            }
    }

    private fun join(state: JoinState) {
        val id = checkNotNull(fetched)
        val events: List<Event>
        val me: MemberId
        if (state.adding) {
            me = MemberId.parse(identifier())
            val member = Member(me, state.name.trim())
            events = listOf(Event.MemberAdded(EventId.parse(identifier()), me, now(), member))
        } else {
            me = checkNotNull(state.chosen)
            events = emptyList()
        }
        val passphrase = (offer as? Offer.Locked)?.let { state.passphrase.toCharArray() }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                syncing.join(
                    id,
                    state.location.settings,
                    state.location.secretKey.trim(),
                    passphrase,
                    me,
                    events,
                )
                fetched = null
                mutable.update { it.copy(busy = false, done = id) }
            } catch (_: Malformed) {
                mutable.update { it.copy(busy = false, rejected = true) }
            } finally {
                passphrase?.fill('\u0000')
            }
        }
    }

    private fun abandon() {
        work?.cancel()
        fetched?.let(syncing::abandon)
        fetched = null
    }

    override fun onCleared() {
        abandon()
    }
}
