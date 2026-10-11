package eu.teele.neugesplitter.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.crypto.Passphrases
import eu.teele.neugesplitter.format.export.ExportFile
import eu.teele.neugesplitter.format.export.ExportedGroup
import eu.teele.neugesplitter.storage.GroupRepository
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportStep {
    ENCRYPTION,
    PASSPHRASE,
    DONE,
}

/**
 * Where the export flow stands (ui-design 3).
 *
 * @property words the generated passphrase, word by word (PR-12a).
 * @property strength how far [passphrase] is from being weak, from 0 to 1 (PR-12b).
 * @property failed the last file chosen could not be written.
 */
data class ExportState(
    val step: ExportStep = ExportStep.ENCRYPTION,
    val count: Int = 0,
    val encrypted: Boolean? = null,
    val words: List<String> = emptyList(),
    val saved: Boolean = false,
    val custom: Boolean = false,
    val passphrase: String = "",
    val strength: Float = 0f,
    val busy: Boolean = false,
    val failed: Boolean = false,
) {
    /** Everything is chosen but the file to write to. */
    val canSave: Boolean
        get() =
            !busy &&
                when (step) {
                    ExportStep.ENCRYPTION -> encrypted == false
                    ExportStep.PASSPHRASE -> if (custom) passphrase.isNotEmpty() else saved
                    ExportStep.DONE -> false
                }

    override fun toString(): String = "ExportState($step, busy=$busy, failed=$failed)"
}

/**
 * Writes groups [ids] to a file the user chooses, encrypted with a passphrase if they choose one,
 * and nothing about any backend (ui-design 3, DA-3, DA-4, PR-12).
 */
class ExportViewModel(
    private val groups: GroupRepository,
    private val ids: List<GroupId>,
    private val today: () -> EntryDate,
    private val locale: Locale,
) : ViewModel() {

    private val mutable = MutableStateFlow(ExportState(count = ids.size))
    val state: StateFlow<ExportState> = mutable.asStateFlow()

    /** What an attacker tries first (TR-25b). */
    private var context: List<String> = emptyList()
    private var measuring: Job? = null

    init {
        viewModelScope.launch {
            context =
                withContext(Dispatchers.IO) {
                    ids.mapNotNull { groups.load(it)?.name }
                        .flatMap { it.lowercase().split(' ') }
                        .filter { it.isNotBlank() }
                }
        }
    }

    /** The name the file is offered under. */
    fun fileName(): String = "neugesplitter-${today()}.zip"

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

    /** On to the passphrase, for an encrypted export; a plain one goes to the file instead. */
    fun next() {
        val state = mutable.value
        if (state.step != ExportStep.ENCRYPTION || state.encrypted != true) return
        if (state.words.isEmpty()) {
            val words = Passphrases.generate(locale)
            try {
                mutable.update { it.copy(words = String(words).split(' ')) }
            } finally {
                words.fill('\u0000')
            }
        }
        mutable.update { it.copy(step = ExportStep.PASSPHRASE) }
    }

    /** Goes back one step; false if this is the first, and the flow should close. */
    fun back(): Boolean {
        val state = mutable.value
        if (state.busy) return true
        when (state.step) {
            ExportStep.ENCRYPTION,
            ExportStep.DONE -> return false
            ExportStep.PASSPHRASE ->
                mutable.update { it.copy(step = ExportStep.ENCRYPTION, failed = false) }
        }
        return true
    }

    /** Writes the export to the file [open] opens, which it is up to this to close. */
    fun save(open: () -> OutputStream) {
        val state = mutable.value
        if (!state.canSave) return
        val passphrase =
            when {
                state.encrypted != true -> null
                state.custom -> state.passphrase.toCharArray()
                else -> state.words.joinToString(" ").toCharArray()
            }
        mutable.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            val written =
                withContext(Dispatchers.IO) {
                    try {
                        val exported =
                            ids.mapNotNull { id ->
                                groups
                                    .texts(id)
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { ExportedGroup(id, it) }
                            }
                        ExportFile.write(exported, open(), passphrase)
                        true
                    } catch (_: IOException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    } finally {
                        passphrase?.fill('\u0000')
                    }
                }
            mutable.update {
                if (written) it.copy(busy = false, step = ExportStep.DONE)
                else it.copy(busy = false, failed = true)
            }
        }
    }
}
