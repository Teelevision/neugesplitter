package eu.teele.neugesplitter.ui.backend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.crypto.Passphrases
import eu.teele.neugesplitter.format.crypto.TooLittleMemory
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.probe.Contents
import eu.teele.neugesplitter.sync.probe.Verdict
import eu.teele.neugesplitter.sync.probe.contents
import eu.teele.neugesplitter.sync.probe.probe
import eu.teele.neugesplitter.work.NewKey
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupStep {
    LOCATION,
    TEST,
    CONTENTS,
    ENCRYPTION,
    PASSPHRASE,
    CONFIRM,
}

/** The location as typed. A bare host is reached over HTTPS. */
data class Location(
    val endpoint: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val region: String = "",
    val pathStyle: Boolean = false,
    val accessKey: String = "",
    val secretKey: String = "",
) {
    val complete: Boolean
        get() = listOf(endpoint, bucket, accessKey, secretKey).all { it.isNotBlank() }

    val settings: BackendSettings
        get() {
            val trimmed = endpoint.trim().trimEnd('/')
            return BackendSettings(
                endpoint = if ("://" in trimmed) trimmed else "https://$trimmed",
                bucket = bucket.trim(),
                prefix = prefix.trim(),
                region = region.trim().ifEmpty { DEFAULT_REGION },
                pathStyle = pathStyle,
                accessKey = accessKey.trim(),
            )
        }

    /** The credentials would cross the network readable (PR-5). */
    val plainHttp: Boolean
        get() = settings.endpoint.startsWith("http://", ignoreCase = true)

    /** These fields, with those [found] names replaced. */
    fun with(found: Pasted): Location =
        Location(
            endpoint = found.endpoint ?: endpoint,
            bucket = found.bucket ?: bucket,
            prefix = found.prefix ?: prefix,
            region = found.region ?: region,
            pathStyle = found.pathStyle ?: pathStyle,
            accessKey = found.accessKey ?: accessKey,
            secretKey = found.secretKey ?: secretKey,
        )

    override fun toString(): String = "Location(redacted)"

    private companion object {
        const val DEFAULT_REGION = "us-east-1"
    }
}

/**
 * Where the backend setup flow stands (ui-design 6).
 *
 * @property connected the group is shared already, so only its own location is accepted: a new
 *   secret, not a move (BE-15 is later).
 * @property invalid the endpoint is no address.
 * @property contents what the location holds; null until the test has read it.
 * @property words the generated passphrase, word by word (PR-12a).
 * @property strength how far [passphrase] is from being weak, from 0 to 1 (PR-12b).
 * @property refused connecting found another group's root object there after all (BE-9).
 * @property memory this device could not lock the passphrase for lack of memory.
 * @property failure why the last attempt to reach the backend failed.
 */
data class BackendSetupState(
    val step: SetupStep = SetupStep.LOCATION,
    val connected: Boolean = false,
    val location: Location = Location(),
    val invalid: Boolean = false,
    val busy: Boolean = false,
    val verdict: Verdict? = null,
    val contents: Contents? = null,
    val encrypted: Boolean? = null,
    val words: List<String> = emptyList(),
    val saved: Boolean = false,
    val custom: Boolean = false,
    val passphrase: String = "",
    val strength: Float = 0f,
    val refused: Boolean = false,
    val memory: Boolean = false,
    val failure: StoreException? = null,
    val done: Boolean = false,
) {
    val readOnly: Boolean
        get() = verdict == Verdict.ReadOnly

    /** The location holds this group, at some state of it (BE-10). */
    val same: Boolean
        get() =
            contents == Contents.SAME || contents == Contents.OLDER || contents == Contents.NEWER

    val canContinue: Boolean
        get() =
            !busy &&
                when (step) {
                    SetupStep.LOCATION -> location.complete
                    SetupStep.TEST ->
                        (verdict == Verdict.Accepted || verdict == Verdict.ReadOnly) &&
                            contents != null
                    SetupStep.CONTENTS ->
                        same || (contents == Contents.EMPTY && !readOnly && !connected)
                    SetupStep.ENCRYPTION -> encrypted != null
                    SetupStep.PASSPHRASE -> if (custom) passphrase.isNotEmpty() else saved
                    SetupStep.CONFIRM -> true
                }

    override fun toString(): String = "BackendSetupState($step, busy=$busy, done=$done)"
}

/**
 * Checks a location, says what it holds, and shares group [group] through it, encrypted with a new
 * passphrase if the user chooses one (ui-design 6, BE-7, BE-9, PR-11).
 */
class BackendSetupViewModel(
    private val groups: GroupRepository,
    private val group: GroupId,
    private val today: () -> EntryDate,
    private val locale: Locale,
    /** The backend at a location, given its secret key; throws for an unusable address. */
    private val stores: (BackendSettings, String) -> LogStore,
    private val newKey: suspend (CharArray) -> NewKey,
    private val connect: suspend (GroupId, BackendSettings, String, NewKey?) -> Boolean,
    /** A location typed in already, as when joining found it empty. */
    prefilled: Location? = null,
) : ViewModel() {

    private val mutable = MutableStateFlow(BackendSetupState(location = prefilled ?: Location()))
    val state: StateFlow<BackendSetupState> = mutable.asStateFlow()

    /** What an attacker tries first (TR-25b). */
    private var context: List<String> = emptyList()
    private var generated: CharArray? = null
    private var key: NewKey? = null
    /** The passphrase [key] was made from, to make it again only when that changes. */
    private var keyFor: String? = null
    private var measuring: Job? = null

    init {
        viewModelScope.launch {
            val (settings, name) =
                withContext(Dispatchers.IO) {
                    groups.backends.settings(group) to groups.load(group)?.name
                }
            context = name?.lowercase()?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
            if (settings != null) {
                mutable.update {
                    it.copy(
                        connected = true,
                        location =
                            Location(
                                endpoint = settings.endpoint,
                                bucket = settings.bucket,
                                prefix = settings.prefix,
                                region = settings.region,
                                pathStyle = settings.pathStyle,
                                accessKey = settings.accessKey,
                            ),
                    )
                }
            }
        }
    }

    fun location(location: Location) =
        mutable.update { it.copy(location = location, invalid = false) }

    /** Fills every field [text] names; false if it names none. */
    fun paste(text: String): Boolean {
        val found = pasted(text)
        if (found.empty) return false
        mutable.update { it.copy(location = it.location.with(found), invalid = false) }
        return true
    }

    fun encrypted(encrypted: Boolean) = mutable.update { it.copy(encrypted = encrypted) }

    fun saved(saved: Boolean) = mutable.update { it.copy(saved = saved) }

    /** Switches between the generated passphrase and one the user types (PR-12b). */
    fun custom(custom: Boolean) = mutable.update { it.copy(custom = custom) }

    fun passphrase(text: String) {
        mutable.update { it.copy(passphrase = text) }
        measuring?.cancel()
        measuring =
            viewModelScope.launch {
                val strength =
                    withContext(Dispatchers.Default) {
                        val chars = text.toCharArray()
                        try {
                            Passphrases.strength(chars, context)
                        } finally {
                            chars.fill('\u0000')
                        }
                    }
                mutable.update { it.copy(strength = strength) }
            }
    }

    /** The generated passphrase, for the copy button. */
    fun generatedText(): String = state.value.words.joinToString(" ")

    fun next() {
        val state = mutable.value
        if (!state.canContinue) return
        when (state.step) {
            SetupStep.LOCATION -> test(state.location)
            SetupStep.TEST -> mutable.update { it.copy(step = SetupStep.CONTENTS) }
            SetupStep.CONTENTS ->
                mutable.update {
                    it.copy(
                        step = if (state.same) SetupStep.CONFIRM else SetupStep.ENCRYPTION,
                        encrypted = if (state.same) null else it.encrypted,
                    )
                }
            SetupStep.ENCRYPTION ->
                if (state.encrypted == true) toPassphrase()
                else mutable.update { it.copy(step = SetupStep.CONFIRM) }
            SetupStep.PASSPHRASE -> lock(state)
            SetupStep.CONFIRM -> finish(state)
        }
    }

    /** Goes back one step; false if this is the first, and the flow should close. */
    fun back(): Boolean {
        val state = mutable.value
        val previous =
            when (state.step) {
                SetupStep.LOCATION -> return false
                SetupStep.TEST -> SetupStep.LOCATION
                SetupStep.CONTENTS -> SetupStep.TEST
                SetupStep.ENCRYPTION -> SetupStep.CONTENTS
                SetupStep.PASSPHRASE -> SetupStep.ENCRYPTION
                SetupStep.CONFIRM ->
                    when {
                        state.same -> SetupStep.CONTENTS
                        state.encrypted == true -> SetupStep.PASSPHRASE
                        else -> SetupStep.ENCRYPTION
                    }
            }
        mutable.update { it.copy(step = previous, failure = null, refused = false, busy = false) }
        return true
    }

    /** Back to the fields, for a location that cannot be used (BE-9). */
    fun elsewhere() =
        mutable.update {
            it.copy(
                step = SetupStep.LOCATION,
                verdict = null,
                contents = null,
                refused = false,
                failure = null,
            )
        }

    private fun test(location: Location) {
        val settings = location.settings
        val store =
            try {
                stores(settings, location.secretKey.trim())
            } catch (_: IllegalArgumentException) {
                mutable.update { it.copy(invalid = true) }
                return
            }
        mutable.update {
            it.copy(
                step = SetupStep.TEST,
                busy = true,
                verdict = null,
                contents = null,
                failure = null,
                refused = false,
            )
        }
        viewModelScope.launch {
            val (verdict, contents) =
                withContext(Dispatchers.IO) {
                    val verdict = probe(store)
                    if (verdict != Verdict.Accepted && verdict != Verdict.ReadOnly) {
                        return@withContext verdict to null
                    }
                    try {
                        val head = groups.local(group, today).head()?.number
                        verdict to contents(store, groups.backends.first(group), head)
                    } catch (e: StoreException) {
                        Verdict.Unusable(e) to null
                    }
                }
            mutable.update { it.copy(busy = false, verdict = verdict, contents = contents) }
        }
    }

    private fun toPassphrase() {
        if (generated == null) {
            val words = Passphrases.generate(locale)
            generated = words
            mutable.update { it.copy(words = String(words).split(' ')) }
        }
        mutable.update { it.copy(step = SetupStep.PASSPHRASE) }
    }

    /** Makes the key once per passphrase, so that a retry publishes the same root object. */
    private fun lock(state: BackendSetupState) {
        val chosen = if (state.custom) state.passphrase else generatedText()
        if (key != null && keyFor == chosen) {
            mutable.update { it.copy(step = SetupStep.CONFIRM) }
            return
        }
        mutable.update { it.copy(busy = true, memory = false) }
        viewModelScope.launch {
            val made =
                try {
                    newKey(chosen.toCharArray())
                } catch (_: TooLittleMemory) {
                    mutable.update { it.copy(busy = false, memory = true) }
                    return@launch
                }
            key?.wipe()
            key = made
            keyFor = chosen
            mutable.update { it.copy(busy = false, step = SetupStep.CONFIRM) }
        }
    }

    private fun finish(state: BackendSetupState) {
        mutable.update { it.copy(busy = true, failure = null, refused = false) }
        val withKey = if (state.encrypted == true && !state.same) key else null
        viewModelScope.launch {
            try {
                val connected =
                    connect(
                        group,
                        state.location.settings,
                        state.location.secretKey.trim(),
                        withKey,
                    )
                if (connected) {
                    mutable.update { it.copy(busy = false, done = true) }
                } else {
                    mutable.update {
                        it.copy(
                            busy = false,
                            refused = true,
                            contents = Contents.FOREIGN,
                            step = SetupStep.CONTENTS,
                        )
                    }
                }
            } catch (e: StoreException) {
                mutable.update { it.copy(busy = false, failure = e) }
            }
        }
    }

    override fun onCleared() {
        generated?.fill('\u0000')
        key?.wipe()
    }
}
