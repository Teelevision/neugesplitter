package eu.teele.neugesplitter.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.balance.Transfer
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Scheme
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.split.Mismatch
import eu.teele.neugesplitter.ui.split.Split
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The first thing that keeps an entry from being saved, stated where Save is (ui-design 5). */
enum class Problem {
    DESCRIPTION,
    AMOUNT,
    PAYERS,
    BENEFICIARIES,
    PAYER_SPLIT,
    BENEFICIARY_SPLIT,
}

/** The two sides of an entry. */
enum class Party {
    PAYERS,
    BENEFICIARIES,
}

/**
 * The entry being written, as typed so far.
 *
 * @property members everyone who can be chosen, in the group's order: the current members, and
 *   removed ones still on the entry.
 * @property source the entry being edited or duplicated. A side that comes out the same as there
 *   stays as it was saved.
 * @property done once the editor should close.
 */
data class EntryEditorState(
    val date: EntryDate,
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val scale: Scale = Scale.DEFAULT,
    val members: List<Member> = emptyList(),
    val source: Entry? = null,
    val kind: Kind = Kind.EXPENSE,
    val description: String = "",
    val amount: Amount? = null,
    val payers: Set<MemberId> = emptySet(),
    val beneficiaries: Set<MemberId> = emptySet(),
    val payerSplit: Split = Split(),
    val beneficiarySplit: Split = Split(),
    val saving: Boolean = false,
    val failed: Boolean = false,
    val done: Boolean = false,
) {
    val problem: Problem?
        get() =
            when {
                description.isBlank() -> Problem.DESCRIPTION
                amount?.isPositive != true -> Problem.AMOUNT
                payers.isEmpty() -> Problem.PAYERS
                beneficiaries.isEmpty() -> Problem.BENEFICIARIES
                mismatch(Party.PAYERS) != null -> Problem.PAYER_SPLIT
                mismatch(Party.BENEFICIARIES) != null -> Problem.BENEFICIARY_SPLIT
                else -> null
            }

    val canSave: Boolean
        get() = loaded && !saving && problem == null

    val payerSide: Side?
        get() = side(Party.PAYERS)

    val beneficiarySide: Side?
        get() = side(Party.BENEFICIARIES)

    fun selected(party: Party): Set<MemberId> = if (party == Party.PAYERS) payers else beneficiaries

    fun split(party: Party): Split = if (party == Party.PAYERS) payerSplit else beneficiarySplit

    /** The members on [party]'s side, in the group's order. */
    fun ordered(party: Party): List<MemberId> =
        selected(party).let { s -> members.map { it.id }.filter { it in s } }

    /** Why [party]'s split cannot be saved, if it cannot. */
    fun mismatch(party: Party): Mismatch? {
        val order = ordered(party)
        if (order.size < 2 || amount == null) return null
        return split(party).mismatch(order, amount)
    }

    private fun previous(party: Party): Side? =
        if (party == Party.PAYERS) source?.payers else source?.beneficiaries

    /**
     * The side as typed. A lone member has nothing to split (SP-1); the split typed for more stays
     * aside for when there are more again.
     */
    private fun side(party: Party): Side? {
        val order = ordered(party)
        if (order.isEmpty()) return null
        val built =
            (if (order.size == 1) Side.Equal(order)
            else split(party).side(order, amount ?: return null)) ?: return null
        val previous = previous(party)
        val same =
            previous != null &&
                previous.members.toSet() == order.toSet() &&
                (previous == built ||
                    previous.scheme == built.scheme && built is Side.Equal ||
                    order.size == 1)
        return if (same) previous else built
    }
}

/**
 * Writes a new entry, or a change to [entry] — or, with [duplicate], a new entry starting out as a
 * copy of it, dated today (EN-1 to EN-9). A new entry starts out as [settling], described as
 * [settlement], when one is given (BA-6).
 */
class EntryEditorViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    entry: EntryId?,
    duplicate: Boolean,
    settling: Transfer?,
    settlement: String,
    private val separator: Char,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
) : ViewModel() {

    private val mutable = MutableStateFlow(EntryEditorState(date = today()))
    val state: StateFlow<EntryEditorState> = mutable.asStateFlow()

    private val id: EntryId = entry?.takeUnless { duplicate } ?: EntryId.parse(identifier())
    private var defaults: Set<MemberId> = emptySet()
    private var beneficiariesChosen = entry != null || settling != null

    init {
        viewModelScope.launch {
            val (loaded, me) =
                withContext(Dispatchers.IO) { repository.load(group) to repository.actingAs(group) }
            if (loaded == null) {
                mutable.update { it.copy(done = true) }
                return@launch
            }
            val source = entry?.let { loaded.entries[it] ?: loaded.deleted[it] }
            val involved =
                source?.let { it.payers.members + it.beneficiaries.members }.orEmpty() +
                    listOfNotNull(settling?.from, settling?.to)
            defaults = loaded.participants().toSet()
            mutable.update { state ->
                val base =
                    state.copy(
                        loaded = true,
                        scale = loaded.scale,
                        members =
                            loaded.members.values.filter {
                                it.id !in loaded.removed || it.id in involved
                            },
                        payers = setOfNotNull(me),
                        beneficiaries = defaults,
                    )
                if (source == null) {
                    if (settling == null) base
                    else
                        base.copy(
                            kind = Kind.TRANSFER,
                            description = settlement,
                            amount = settling.amount,
                            payers = setOf(settling.from),
                            beneficiaries = setOf(settling.to),
                        )
                } else
                    base.copy(
                        editing = !duplicate,
                        source = source,
                        kind = source.kind,
                        description = source.description,
                        amount = source.amount,
                        date = if (duplicate) state.date else source.date,
                        payers = source.payers.members.toSet(),
                        beneficiaries = source.beneficiaries.members.toSet(),
                        payerSplit = Split.of(source.payers, loaded.scale, separator),
                        beneficiarySplit = Split.of(source.beneficiaries, loaded.scale, separator),
                    )
            }
        }
    }

    /**
     * Chooses [kind]. A transfer goes to somebody in particular, so until the user has chosen the
     * beneficiaries, a transfer starts with none and the other kinds with the defaults (EN-7).
     */
    fun kind(kind: Kind) =
        mutable.update {
            val beneficiaries =
                when {
                    beneficiariesChosen -> it.beneficiaries
                    kind == Kind.TRANSFER -> emptySet()
                    else -> defaults
                }
            it.copy(kind = kind, beneficiaries = beneficiaries)
        }

    fun description(text: String) = mutable.update { it.copy(description = text) }

    /** Takes the calculator's committed result (AM-5). */
    fun amount(amount: Amount) = mutable.update { it.copy(amount = amount) }

    fun date(date: EntryDate) = mutable.update { it.copy(date = date) }

    fun payer(member: MemberId) = mutable.update { it.copy(payers = it.payers.toggled(member)) }

    fun beneficiary(member: MemberId) {
        beneficiariesChosen = true
        mutable.update { it.copy(beneficiaries = it.beneficiaries.toggled(member)) }
    }

    /** Chooses how [party]'s side is split (SP-1). */
    fun scheme(party: Party, scheme: Scheme) = split(party) { it.copy(scheme = scheme) }

    /** Types [text] as [member]'s input to [party]'s split, if it fits there. */
    fun typed(party: Party, member: MemberId, text: String) {
        val state = mutable.value
        if (state.split(party).accepts(member, text, state.scale)) {
            split(party) { it.typed(member, text) }
        }
    }

    private fun split(party: Party, change: (Split) -> Split) =
        mutable.update {
            if (party == Party.PAYERS) it.copy(payerSplit = change(it.payerSplit))
            else it.copy(beneficiarySplit = change(it.beneficiarySplit))
        }

    fun save() {
        val state = mutable.value
        if (!state.canSave) return
        val written =
            Entry(
                id = id,
                kind = state.kind,
                description = state.description.trim(),
                date = state.date,
                amount = state.amount!!,
                payers = state.payerSide!!,
                beneficiaries = state.beneficiarySide!!,
                note = state.source?.note,
                attachments = state.source?.attachments,
            )
        val source = state.source
        val changes =
            if (state.editing && source != null) EntryChanges.between(source, written) else null
        if (changes?.isEmpty == true) {
            mutable.update { it.copy(done = true) }
            return
        }
        mutable.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            val stored =
                withContext(Dispatchers.IO) {
                    val by = repository.actingAs(group) ?: return@withContext false
                    val event =
                        if (changes != null) {
                            Event.EntryUpdated(EventId.parse(identifier()), by, now(), id, changes)
                        } else {
                            Event.EntryCreated(EventId.parse(identifier()), by, now(), written)
                        }
                    try {
                        repository.append(group, listOf(event), today())
                        true
                    } catch (_: Malformed) {
                        // A description past the limits every reader holds to (format-spec 2.2).
                        false
                    }
                }
            mutable.update { it.copy(saving = false, failed = !stored, done = stored) }
        }
    }

    private fun Set<MemberId>.toggled(member: MemberId) =
        if (member in this) this - member else this + member
}
