package eu.teele.neugesplitter.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.format.export.ExportFile
import eu.teele.neugesplitter.format.export.ExportedGroup
import eu.teele.neugesplitter.format.export.Locked
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.storage.GroupRepository
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportStep {
    READING,
    PASSPHRASE,
    FAILED,
    GROUPS,
    WHO,
}

/** Why a file offers nothing to import. */
enum class ImportProblem {
    /** No export file, or a damaged one. */
    UNREADABLE,
    /** A later version of the app wrote it. */
    UNSUPPORTED,
    EMPTY,
}

/**
 * A group in the file.
 *
 * @property group the group its history adds up to; null if that cannot be read.
 * @property unsupported a later version of the app wrote its history.
 * @property imported it is on this device now, as a new group.
 */
data class Candidate(
    val group: Group?,
    val unsupported: Boolean = false,
    val imported: Boolean = false,
) {
    val importable: Boolean
        get() = group != null && !imported
}

/**
 * Where the import flow stands (ui-design 3).
 *
 * @property wrong the passphrase typed does not open the file.
 * @property chosen the indices into [candidates] of the groups to import.
 * @property current the index into [candidates] of the group to choose who to be in.
 * @property member the member chosen; null for a new one, named [name].
 * @property rejected the new member's name is past the limits every reader holds to.
 * @property done the groups imported, once every one chosen is.
 */
data class ImportState(
    val step: ImportStep = ImportStep.READING,
    val passphrase: String = "",
    val wrong: Boolean = false,
    val busy: Boolean = true,
    val problem: ImportProblem? = null,
    val candidates: List<Candidate> = emptyList(),
    val chosen: Set<Int> = emptySet(),
    val current: Int? = null,
    val member: MemberId? = null,
    val adding: Boolean = false,
    val name: String = "",
    val rejected: Boolean = false,
    val done: List<GroupId>? = null,
) {
    /** The group to choose who to be in. */
    val group: Group?
        get() = current?.let { candidates[it].group }

    val canContinue: Boolean
        get() =
            !busy &&
                when (step) {
                    ImportStep.READING,
                    ImportStep.FAILED -> false
                    ImportStep.PASSPHRASE -> passphrase.isNotEmpty()
                    ImportStep.GROUPS -> chosen.isNotEmpty()
                    ImportStep.WHO -> if (adding) name.isNotBlank() else member != null
                }

    override fun toString(): String = "ImportState($step, busy=$busy, done=$done)"
}

/**
 * Reads an export file, unlocks it if it is encrypted, and imports the groups chosen from it, each
 * as a new group that is not shared, after asking who the user is in it (ui-design 3, DA-3).
 */
class ImportViewModel(
    private val groups: GroupRepository,
    /** Opens the file; throws an [IOException] or a [SecurityException] if it cannot. */
    private val open: () -> InputStream,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
) : ViewModel() {

    private val mutable = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = mutable.asStateFlow()

    /** The groups as the file holds them, in the order of [ImportState.candidates]. */
    private var file: List<ExportedGroup> = emptyList()
    private val imported = mutableListOf<GroupId>()

    init {
        read(null)
    }

    fun passphrase(text: String) = mutable.update { it.copy(passphrase = text, wrong = false) }

    fun toggle(index: Int) {
        if (mutable.value.candidates.getOrNull(index)?.importable != true) return
        mutable.update {
            it.copy(chosen = if (index in it.chosen) it.chosen - index else it.chosen + index)
        }
    }

    fun choose(member: MemberId) = mutable.update { it.copy(member = member, adding = false) }

    fun adding() = mutable.update { it.copy(member = null, adding = true) }

    fun name(text: String) = mutable.update { it.copy(name = text, rejected = false) }

    fun next() {
        val state = mutable.value
        if (!state.canContinue) return
        when (state.step) {
            ImportStep.READING,
            ImportStep.FAILED -> Unit
            ImportStep.PASSPHRASE -> read(state.passphrase)
            ImportStep.GROUPS -> who(state.chosen.min())
            ImportStep.WHO -> import(state)
        }
    }

    /** Goes back one step; false if this is the first, and the flow should close. */
    fun back(): Boolean {
        val state = mutable.value
        if (state.step != ImportStep.WHO) return false
        // The group is being imported, and is either way.
        if (state.busy) return true
        mutable.update { it.copy(step = ImportStep.GROUPS, current = null) }
        return true
    }

    private fun read(passphrase: String?) {
        mutable.update { it.copy(busy = true, wrong = false) }
        viewModelScope.launch {
            val chars = passphrase?.toCharArray()
            try {
                val read =
                    withContext(Dispatchers.IO) {
                        val read = open().use { ExportFile.read(it, chars) }
                        read to read.map(::candidate)
                    }
                file = read.first
                val candidates = read.second
                mutable.update {
                    if (candidates.isEmpty()) {
                        it.copy(
                            busy = false,
                            step = ImportStep.FAILED,
                            problem = ImportProblem.EMPTY,
                        )
                    } else {
                        it.copy(
                            busy = false,
                            step = ImportStep.GROUPS,
                            candidates = candidates,
                            chosen =
                                candidates.indices.filterTo(mutableSetOf()) { index ->
                                    candidates[index].importable
                                },
                        )
                    }
                }
            } catch (_: Locked) {
                mutable.update { it.copy(busy = false, step = ImportStep.PASSPHRASE) }
            } catch (_: WrongPassphrase) {
                mutable.update { it.copy(busy = false, wrong = true) }
            } catch (_: Unsupported) {
                failed(ImportProblem.UNSUPPORTED)
            } catch (_: Malformed) {
                failed(ImportProblem.UNREADABLE)
            } catch (_: IOException) {
                failed(ImportProblem.UNREADABLE)
            } catch (_: SecurityException) {
                failed(ImportProblem.UNREADABLE)
            } finally {
                chars?.fill('\u0000')
            }
        }
    }

    private fun failed(problem: ImportProblem) =
        mutable.update { it.copy(busy = false, step = ImportStep.FAILED, problem = problem) }

    private fun candidate(exported: ExportedGroup): Candidate =
        try {
            Candidate(Group.replay(exported.events.map(EventJson::read)))
        } catch (_: Malformed) {
            Candidate(null)
        } catch (_: Unsupported) {
            Candidate(null, unsupported = true)
        }

    private fun who(index: Int) =
        mutable.update {
            it.copy(
                step = ImportStep.WHO,
                current = index,
                member = null,
                adding = false,
                name = "",
                rejected = false,
            )
        }

    private fun import(state: ImportState) {
        val index = checkNotNull(state.current)
        val events: List<Event>
        val me: MemberId
        if (state.adding) {
            me = MemberId.parse(identifier())
            val member = Member(me, state.name.trim())
            events = listOf(Event.MemberAdded(EventId.parse(identifier()), me, now(), member))
        } else {
            me = checkNotNull(state.member)
            events = emptyList()
        }
        val id = GroupId.parse(identifier())
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    groups.importGroup(id, file[index].events, me, events, today())
                }
            } catch (_: Malformed) {
                mutable.update { it.copy(busy = false, rejected = true) }
                return@launch
            }
            imported += id
            val candidates =
                state.candidates.toMutableList().apply {
                    this[index] = this[index].copy(imported = true)
                }
            val chosen = state.chosen - index
            mutable.update { it.copy(busy = false, candidates = candidates, chosen = chosen) }
            val following = chosen.minOrNull()
            if (following == null) {
                mutable.update { it.copy(done = imported.toList()) }
            } else {
                who(following)
            }
        }
    }
}
