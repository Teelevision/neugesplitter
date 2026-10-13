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
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Occurrence
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Series
import eu.teele.neugesplitter.domain.recurrence.Stop
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
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
 * @property blank a new entry with nothing prefilled.
 * @property recurrence how the entry repeats, if it does (RE-1).
 * @property occurrence whether the entry being edited came from a series (RE-15).
 * @property was how that series repeated on the occurrence's due date.
 * @property fixed whether somebody confirmed or edited a later occurrence of that series, so that
 *   the series can no longer change from this one; it can still stop after it (RE-6).
 * @property asking whether to ask if a change to an occurrence goes for the rest of the series too
 *   (RE-6).
 * @property done once the editor should close.
 */
data class EntryEditorState(
    val date: EntryDate,
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val blank: Boolean = false,
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
    val recurrence: Recurrence? = null,
    val occurrence: Boolean = false,
    val was: Recurrence? = null,
    val fixed: Boolean = false,
    val asking: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val done: Boolean = false,
) {
    val problem: Problem?
        get() =
            when {
                amount?.isPositive != true -> Problem.AMOUNT
                description.isBlank() -> Problem.DESCRIPTION
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
 *
 * A recurring entry is a series whose first occurrence is the entry itself (RE-1). An [entry] that
 * came from a series is changed as that occurrence, and the series with it from there on if the
 * user says so (RE-6, RE-15).
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
    private var occurrence: Occurrence? = null
    private var series: Series? = null

    init {
        viewModelScope.launch {
            val (loaded, me) =
                withContext(Dispatchers.IO) { repository.load(group) to repository.actingAs(group) }
            if (loaded == null) {
                mutable.update { it.copy(done = true) }
                return@launch
            }
            val occurrence = entry?.let { loaded.occurrence(it, today()) }
            val source =
                entry?.let { loaded.entries[it] ?: loaded.deleted[it] ?: occurrence?.entry }
            val series = occurrence?.takeUnless { duplicate }?.let { loaded.series[it.series] }
            this@EntryEditorViewModel.occurrence = occurrence?.takeIf { series != null }
            this@EntryEditorViewModel.series = series
            val was =
                occurrence
                    ?.let { series?.segment(it.due) }
                    ?.let { Recurrence(it.interval, it.mode) }
            val involved =
                source?.let { it.payers.members + it.beneficiaries.members }.orEmpty() +
                    listOfNotNull(settling?.from, settling?.to)
            defaults = loaded.participants().toSet()
            mutable.update { state ->
                val base =
                    state.copy(
                        loaded = true,
                        blank = entry == null && settling == null,
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
                        recurrence = was,
                        occurrence = series != null,
                        was = was,
                        fixed = occurrence != null && series?.decidedAfter(occurrence.due) == true,
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

    /** Takes [split] for [party]'s side, as the split sheet confirms it. */
    fun split(party: Party, split: Split) = split(party) { split }

    private fun split(party: Party, change: (Split) -> Split) =
        mutable.update {
            if (party == Party.PAYERS) it.copy(payerSplit = change(it.payerSplit))
            else it.copy(beneficiarySplit = change(it.beneficiarySplit))
        }

    /** Makes the entry repeat as [recurrence], or stop repeating with null (RE-1). */
    fun recurrence(recurrence: Recurrence?) = mutable.update { it.copy(recurrence = recurrence) }

    /** Leaves the question of [EntryEditorState.asking] unanswered, and the entry unsaved. */
    fun dismiss() = mutable.update { it.copy(asking = false) }

    /**
     * Saves the entry. A change to an occurrence that could go for the rest of the series as well
     * asks first; [following] is the answer (RE-6).
     */
    fun save(following: Boolean? = null) {
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
        val occurrence = occurrence
        val recurrence = state.recurrence
        if (
            occurrence != null &&
                following == null &&
                recurrence != null &&
                recurrence == state.was &&
                written != occurrence.entry
        ) {
            mutable.update { it.copy(asking = true) }
            return
        }
        val events: (MemberId) -> List<Event> =
            when {
                occurrence != null -> { by ->
                        changed(
                            occurrence,
                            written,
                            recurrence,
                            state.was,
                            following == true,
                            state.fixed,
                            by,
                        )
                    }
                recurrence != null -> { by -> started(written, recurrence, state.editing, by) }
                else -> { by -> listOfNotNull(entry(written, state, by)) }
            }
        mutable.update { it.copy(asking = false, saving = true, failed = false) }
        viewModelScope.launch {
            val stored =
                withContext(Dispatchers.IO) {
                    val by = repository.actingAs(group) ?: return@withContext false
                    try {
                        events(by)
                            .takeIf { it.isNotEmpty() }
                            ?.let { repository.append(group, it, today()) }
                        true
                    } catch (_: Malformed) {
                        // A description past the limits every reader holds to (format-spec 2.2).
                        false
                    }
                }
            mutable.update { it.copy(saving = false, failed = !stored, done = stored) }
        }
    }

    /** A plain entry, created or changed; null if nothing changed. */
    private fun entry(written: Entry, state: EntryEditorState, by: MemberId): Event? {
        val source = state.source
        if (!state.editing || source == null) return Event.EntryCreated(event(), by, now(), written)
        val changes = EntryChanges.between(source, written)
        return if (changes.isEmpty) null else Event.EntryUpdated(event(), by, now(), id, changes)
    }

    /**
     * A new series, whose first occurrence is [written]: confirmed right away if the series only
     * suggests, since it is being entered now, and amended onto its date if the pattern does not
     * produce that date, which would otherwise leave nothing of it (format-spec 4.2). An entry
     * [editing] makes way for it.
     */
    private fun started(
        written: Entry,
        recurrence: Recurrence,
        editing: Boolean,
        by: MemberId,
    ): List<Event> {
        val series = SeriesId.parse(identifier())
        val start = written.date
        val template = Template.of(written)
        val segment = Segment(recurrence.interval, recurrence.mode, template)
        val produced = recurrence.interval.dates(start.value, start.value).any()
        val first = EventId.occurrence(series, start)
        // One moment for all, which tells the history they are one step (HI-2).
        val at = now()
        return listOfNotNull(
            if (editing) Event.EntryDeleted(event(), by, at, id) else null,
            Event.SeriesCreated(event(), by, at, series, start, segment),
            when {
                !produced -> Event.OccurrenceAmended(first, by, at, series, start, start, template)
                recurrence.mode == Mode.SUGGESTED ->
                    Event.OccurrenceConfirmed(first, by, at, series, start)
                else -> null
            },
        )
    }

    /**
     * [occurrence] as [written], and its series as [recurrence] from there on — [following] the
     * occurrence when it repeats as it [was], stopping after it when it no longer repeats (RE-6,
     * RE-7). A series [fixed] by a later decision only stops, as amending it would rewrite what
     * somebody confirmed or edited. The occurrence is amended only where the series does not
     * already produce it as written.
     */
    private fun changed(
        occurrence: Occurrence,
        written: Entry,
        recurrence: Recurrence?,
        was: Recurrence?,
        following: Boolean,
        fixed: Boolean,
        by: MemberId,
    ): List<Event> {
        val series = checkNotNull(series)
        val due = occurrence.due
        val template = Template.of(written)
        val at = now()
        val (change, after) =
            when {
                recurrence == null ->
                    if (was == null) null to series
                    else {
                        val next = EntryDate.of(due.value.plusDays(1))
                        Event.SeriesStopped(event(), by, at, series.id, next) to
                            series.stopped(Stop(next))
                    }
                fixed -> null to series
                recurrence != was || following -> {
                    val segment = Segment(recurrence.interval, recurrence.mode, template)
                    Event.SeriesAmended(event(), by, at, series.id, due, segment) to
                        series.from(due, segment).restored()
                }
                else -> null to series
            }
        val produced = after.occurrences(maxOf(today(), due)).find { it.due == due }
        val decided = EventId.occurrence(series.id, due)
        val decision =
            when {
                produced?.entry != written ->
                    Event.OccurrenceAmended(decided, by, at, series.id, due, written.date, template)
                produced.pending -> Event.OccurrenceConfirmed(decided, by, at, series.id, due)
                else -> null
            }
        return listOfNotNull(change, decision)
    }

    private fun event() = EventId.parse(identifier())

    private fun Set<MemberId>.toggled(member: MemberId) =
        if (member in this) this - member else this + member
}
