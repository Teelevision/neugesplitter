package eu.teele.neugesplitter.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
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
}

/**
 * The entry being written, as typed so far.
 *
 * @property members everyone who can be chosen, in the group's order: the current members, and
 *   removed ones still on the entry.
 * @property source the entry being edited or duplicated. A side whose members are unchanged keeps
 *   its scheme from there.
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
    val amount: String = "",
    val payers: Set<MemberId> = emptySet(),
    val beneficiaries: Set<MemberId> = emptySet(),
    val saving: Boolean = false,
    val failed: Boolean = false,
    val done: Boolean = false,
) {
    val parsed: Amount?
        get() = typed(amount, scale)

    val problem: Problem?
        get() =
            when {
                description.isBlank() -> Problem.DESCRIPTION
                parsed == null -> Problem.AMOUNT
                payers.isEmpty() -> Problem.PAYERS
                beneficiaries.isEmpty() -> Problem.BENEFICIARIES
                else -> null
            }

    val canSave: Boolean
        get() = loaded && !saving && problem == null

    val payerSide: Side?
        get() = side(payers, source?.payers)

    val beneficiarySide: Side?
        get() = side(beneficiaries, source?.beneficiaries)

    private fun side(selected: Set<MemberId>, previous: Side?): Side? =
        when {
            selected.isEmpty() -> null
            previous != null && previous.members.toSet() == selected -> previous
            else -> Side.Equal(members.map { it.id }.filter { it in selected })
        }
}

/**
 * An amount as typed into the plain field that stands in until the calculator arrives: digits, and
 * at most [scale] of them after a point or comma. Null if that is not what [text] is, or if it is
 * not a positive amount the format can hold (AM-6, AM-7).
 */
internal fun typed(text: String, scale: Scale): Amount? {
    val match = TYPED.matchEntire(text) ?: return null
    val (whole, fraction) = match.destructured
    if (fraction.length > scale.digits) return null
    val units = BigDecimal("$whole.${fraction.ifEmpty { "0" }}").movePointRight(Amount.UNIT_DIGITS)
    if (units.signum() == 0 || units >= BigDecimal.valueOf(Amount.LIMIT)) return null
    return Amount.ofUnits(units.longValueExact())
}

private val TYPED = Regex("""(\d*)(?:[.,](\d*))?""")

/** [amount] as [typed] reads it back, with this device's decimal separator. */
private fun typing(amount: Amount, scale: Scale): String =
    BigDecimal.valueOf(amount.units, Amount.UNIT_DIGITS)
        .setScale(scale.digits, RoundingMode.HALF_UP)
        .toPlainString()
        .replace('.', DecimalFormatSymbols.getInstance().decimalSeparator)

/**
 * Writes a new entry, or a change to [entry] — or, with [duplicate], a new entry starting out as a
 * copy of it, dated today (EN-1 to EN-9).
 */
class EntryEditorViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    entry: EntryId?,
    duplicate: Boolean,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
) : ViewModel() {

    private val mutable = MutableStateFlow(EntryEditorState(date = today()))
    val state: StateFlow<EntryEditorState> = mutable.asStateFlow()

    private val id: EntryId = entry?.takeUnless { duplicate } ?: EntryId.parse(identifier())
    private var defaults: Set<MemberId> = emptySet()
    private var beneficiariesChosen = entry != null

    init {
        viewModelScope.launch {
            val (loaded, me) =
                withContext(Dispatchers.IO) { repository.load(group) to repository.actingAs(group) }
            if (loaded == null) {
                mutable.update { it.copy(done = true) }
                return@launch
            }
            val source = entry?.let { loaded.entries[it] ?: loaded.deleted[it] }
            val involved = source?.let { it.payers.members + it.beneficiaries.members }.orEmpty()
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
                if (source == null) base
                else
                    base.copy(
                        editing = !duplicate,
                        source = source,
                        kind = source.kind,
                        description = source.description,
                        amount = typing(source.amount, loaded.scale),
                        date = if (duplicate) state.date else source.date,
                        payers = source.payers.members.toSet(),
                        beneficiaries = source.beneficiaries.members.toSet(),
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

    /** Takes [text] if it can still become an amount, and ignores it otherwise. */
    fun amount(text: String) =
        mutable.update {
            val partial = Regex("""\d{0,10}(?:[.,]\d{0,${it.scale.digits}})?""")
            if (partial.matches(text)) it.copy(amount = text) else it
        }

    fun date(date: EntryDate) = mutable.update { it.copy(date = date) }

    fun payer(member: MemberId) = mutable.update { it.copy(payers = it.payers.toggled(member)) }

    fun beneficiary(member: MemberId) {
        beneficiariesChosen = true
        mutable.update { it.copy(beneficiaries = it.beneficiaries.toggled(member)) }
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
                amount = state.parsed!!,
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
