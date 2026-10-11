package eu.teele.neugesplitter.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.format.export.ExportFile
import eu.teele.neugesplitter.format.export.ExportedGroup
import eu.teele.neugesplitter.format.export.Locked
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.format.splitwise.MixedCurrencies
import eu.teele.neugesplitter.format.splitwise.SplitwiseExport
import eu.teele.neugesplitter.storage.GroupRepository
import java.io.BufferedInputStream
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
    SPLITWISE,
    WHO,
}

/** Why a file offers nothing to import. */
enum class ImportProblem {
    /** No export file, or a damaged one. */
    UNREADABLE,
    /** A later version of the app wrote it. */
    UNSUPPORTED,
    EMPTY,
    /** A Splitwise export in more than one currency. */
    CURRENCIES,
}

/**
 * What a Splitwise export becomes (DA-5).
 *
 * @property group the group it becomes, under a name of its own until the user gives one.
 * @property skipped rows that move no money, which become no entry.
 * @property difference the largest difference between a balance here and its total in the file;
 *   null if the file has no totals.
 * @property correction an entry, added last, that brings every balance to its total; null if none
 *   can.
 */
data class SplitwisePreview(
    val group: Group,
    val expenses: Int,
    val transfers: Int,
    val skipped: Int,
    val difference: Amount?,
    val correction: Entry?,
)

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
 * @property splitwise the file is a Splitwise export, to become one group named [title].
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
    val splitwise: SplitwisePreview? = null,
    val title: String = "",
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
                    ImportStep.SPLITWISE -> title.isNotBlank()
                    ImportStep.WHO -> if (adding) name.isNotBlank() else member != null
                }

    override fun toString(): String = "ImportState($step, busy=$busy, done=$done)"
}

/**
 * Reads an export file, unlocks it if it is encrypted, and imports the groups chosen from it, each
 * as a new group that is not shared, after asking who the user is in it (ui-design 3, DA-3). A file
 * that is not a zip is read as a Splitwise export, which becomes one such group once the user has
 * named it (DA-5).
 */
class ImportViewModel(
    private val groups: GroupRepository,
    /** Opens the file; throws an [IOException] or a [SecurityException] if it cannot. */
    private val open: () -> InputStream,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    /** The description of an entry that corrects a Splitwise export's balances to its totals. */
    private val correction: String,
) : ViewModel() {

    private val mutable = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = mutable.asStateFlow()

    /** The groups as the file holds them, in the order of [ImportState.candidates]. */
    private var file: List<ExportedGroup> = emptyList()
    private val imported = mutableListOf<GroupId>()

    /** The members of a Splitwise export, in its order, and the entries its rows become. */
    private var columns: List<Member> = emptyList()
    private var entries: List<Entry> = emptyList()

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

    fun title(text: String) = mutable.update { it.copy(title = text) }

    fun next() {
        val state = mutable.value
        if (!state.canContinue) return
        when (state.step) {
            ImportStep.READING,
            ImportStep.FAILED -> Unit
            ImportStep.PASSPHRASE -> read(state.passphrase)
            ImportStep.GROUPS -> who(state.chosen.min())
            ImportStep.SPLITWISE -> {
                val group = checkNotNull(state.splitwise).group.copy(name = state.title.trim())
                mutable.update { it.copy(candidates = listOf(Candidate(group))) }
                who(0)
            }
            ImportStep.WHO -> if (state.splitwise != null) splitwise(state) else import(state)
        }
    }

    /** Goes back one step; false if this is the first, and the flow should close. */
    fun back(): Boolean {
        val state = mutable.value
        if (state.step != ImportStep.WHO) return false
        // The group is being imported, and is either way.
        if (state.busy) return true
        val previous = if (state.splitwise != null) ImportStep.SPLITWISE else ImportStep.GROUPS
        mutable.update { it.copy(step = previous, current = null) }
        return true
    }

    private fun read(passphrase: String?) {
        mutable.update { it.copy(busy = true, wrong = false) }
        viewModelScope.launch {
            val chars = passphrase?.toCharArray()
            try {
                val read =
                    withContext(Dispatchers.IO) {
                        open().use { stream ->
                            val input = BufferedInputStream(stream)
                            input.mark(ZIP.size)
                            val zip = input.readNBytes(ZIP.size).contentEquals(ZIP)
                            input.reset()
                            if (zip) {
                                val read = ExportFile.read(input, chars)
                                Read.Groups(read, read.map(::candidate))
                            } else {
                                preview(SplitwiseExport.read(input))
                            }
                        }
                    }
                if (read is Read.Splitwise) {
                    columns = read.columns
                    entries = read.entries
                    mutable.update {
                        it.copy(busy = false, step = ImportStep.SPLITWISE, splitwise = read.preview)
                    }
                    return@launch
                }
                read as Read.Groups
                file = read.file
                val candidates = read.candidates
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
            } catch (_: MixedCurrencies) {
                failed(ImportProblem.CURRENCIES)
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

    /** What the file turned out to hold. */
    private sealed interface Read {
        class Groups(val file: List<ExportedGroup>, val candidates: List<Candidate>) : Read

        class Splitwise(
            val columns: List<Member>,
            val entries: List<Entry>,
            val preview: SplitwisePreview,
        ) : Read
    }

    /** @throws Malformed if a row cannot become an entry. */
    private fun preview(export: SplitwiseExport): Read.Splitwise {
        val columns = export.members.map { Member(MemberId.parse(identifier()), it) }
        val ids = columns.map { it.id }
        val entries = export.entries(ids) { EntryId.parse(identifier()) }
        val group = Group.replay(splitwiseEvents(PREVIEW, "Splitwise", columns, entries))
        val balances = group.settlement(today())
        val correction =
            export.correction(balances, ids, EntryId.parse(identifier()), correction, today())
        val preview =
            SplitwisePreview(
                group = group,
                expenses = entries.count { it.kind != Kind.TRANSFER },
                transfers = entries.count { it.kind == Kind.TRANSFER },
                skipped = export.rows.size - entries.size,
                difference = export.difference(balances, ids),
                correction = correction,
            )
        return Read.Splitwise(columns, entries, preview)
    }

    /**
     * The events of a new group [id] with [members], the first of whom creates it and every entry.
     */
    private fun splitwiseEvents(
        id: GroupId,
        name: String,
        members: List<Member>,
        entries: List<Entry>,
    ): List<Event> {
        val at = now()
        val eventId = { EventId.parse(identifier()) }
        val by = members.first().id
        return Group.create(id, name, null, null, null, members, at, eventId) +
            entries.map { Event.EntryCreated(eventId(), by, at, it) }
    }

    private fun splitwise(state: ImportState) {
        val me: MemberId
        val members: List<Member>
        if (state.adding) {
            me = MemberId.parse(identifier())
            members = listOf(Member(me, state.name.trim())) + columns
        } else {
            me = checkNotNull(state.member)
            members = columns.sortedBy { it.id != me }
        }
        val id = GroupId.parse(identifier())
        val correction = checkNotNull(state.splitwise).correction
        val events =
            splitwiseEvents(id, state.title.trim(), members, entries + listOfNotNull(correction))
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { groups.create(events, today()) }
            } catch (_: Malformed) {
                mutable.update { it.copy(busy = false, rejected = true) }
                return@launch
            }
            mutable.update { it.copy(busy = false, done = listOf(id)) }
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

    private companion object {
        /** How every zip file begins. */
        val ZIP = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())

        /** The identifier of a Splitwise group before it is imported, which is never stored. */
        val PREVIEW: GroupId = GroupId.parse("0".repeat(32))
    }
}
