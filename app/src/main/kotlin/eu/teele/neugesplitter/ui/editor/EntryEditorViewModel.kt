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
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Decision
import eu.teele.neugesplitter.domain.recurrence.Impact
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
    DATE,
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
 * @property blank a new entry with nothing prefilled but an amount, which the calculator opens on.
 * @property recurrence how the entry repeats, if it does (RE-1).
 * @property occurrence whether the entry being edited came from a series (RE-15).
 * @property was how that series repeated on the occurrence's due date.
 * @property due the occurrence's due date.
 * @property scheduled whether the series makes [due] due; an amended occurrence may stand where it
 *   no longer does (format-spec 4.2).
 * @property changing whether this is the series itself, changed from [date] on (RE-6).
 * @property earliest the first date a changed rule can start on: after the last occurrence somebody
 *   confirmed or edited before it, whose content it would otherwise change (format-spec 4.2).
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
    val due: EntryDate? = null,
    val scheduled: Boolean = true,
    val changing: Boolean = false,
    val earliest: EntryDate? = null,
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
                (amending || changing) && earliest != null && date < earliest -> Problem.DATE
                else -> null
            }

    val canSave: Boolean
        get() = loaded && !saving && problem == null

    /**
     * Whether the occurrence's series changes its rule, from the occurrence's date on, which makes
     * it the first of the new rule (RE-6).
     */
    val amending: Boolean
        get() = occurrence && !fixed && recurrence != null && recurrence != was

    /**
     * Whether the occurrence is due on a date its series does not make due, as it will repeat: with
     * a changed rule, on its own date.
     */
    val offSchedule: Boolean
        get() =
            if (amending) !recurrence!!.interval.dates(date.value, date.value).any() else !scheduled

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
 * [settlement], when one is given (BA-6), or else with the amount [prefilled], as selected in
 * another app (AM-9).
 *
 * A recurring entry is a series whose first occurrence is the entry itself (RE-1). An [entry] that
 * came from a series is changed as that occurrence, and the series with it from there on if the
 * user says so (RE-6, RE-15). With [changing], it is that series itself, changed from a date on
 * without any occurrence (RE-6).
 */
class EntryEditorViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    entry: EntryId?,
    duplicate: Boolean,
    settling: Transfer?,
    settlement: String,
    private val transfer: String,
    private val separator: Char,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    private val changing: SeriesId? = null,
    private val prefilled: Amount? = null,
) : ViewModel() {

    private val mutable = MutableStateFlow(EntryEditorState(date = today()))
    val state: StateFlow<EntryEditorState> = mutable.asStateFlow()

    private val id: EntryId = entry?.takeUnless { duplicate } ?: EntryId.parse(identifier())
    private var defaults: Set<MemberId> = emptySet()
    private var beneficiariesChosen = entry != null || settling != null || changing != null
    private var occurrence: Occurrence? = null
    private var series: Series? = null
    private var loaded: Group? = null
    private var me: MemberId? = null

    init {
        viewModelScope.launch {
            val (loaded, me) =
                withContext(Dispatchers.IO) { repository.load(group) to repository.actingAs(group) }
            val changed = changing?.let { loaded?.series?.get(it) }?.takeUnless { it.deleted }
            if (loaded == null || changing != null && changed == null) {
                mutable.update { it.copy(done = true) }
                return@launch
            }
            this@EntryEditorViewModel.loaded = loaded
            this@EntryEditorViewModel.me = me
            val occurrence = entry?.let { loaded.occurrence(it, today()) }
            val series =
                changed ?: occurrence?.takeUnless { duplicate }?.let { loaded.series[it.series] }
            this@EntryEditorViewModel.occurrence = occurrence?.takeIf { series != null }
            this@EntryEditorViewModel.series = series
            val from = changed?.let { from(it) }
            val source =
                entry?.let { loaded.entries[it] ?: loaded.deleted[it] ?: occurrence?.entry }
                    ?: from?.let { changed.segment(it)?.template?.on(id, it) }
            val was =
                (from ?: occurrence?.due)
                    ?.let { series?.segment(it) }
                    ?.let { Recurrence(it.interval, it.mode) }
            val involved =
                source?.let { it.payers.members + it.beneficiaries.members }.orEmpty() +
                    listOfNotNull(settling?.from, settling?.to)
            defaults = loaded.participants().toSet()
            mutable.update { state ->
                val base =
                    state.copy(
                        loaded = true,
                        blank = entry == null && settling == null && changing == null,
                        scale = loaded.scale,
                        members =
                            loaded.members.values.filter {
                                it.id !in loaded.removed || it.id in involved
                            },
                        payers = setOfNotNull(me),
                        beneficiaries = defaults,
                    )
                if (source == null) {
                    if (settling == null)
                        base.copy(
                            amount = prefilled?.roundedTo(loaded.scale)?.takeIf { it.isPositive }
                        )
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
                        editing = !duplicate && changed == null,
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
                        occurrence = series != null && changed == null,
                        was = was,
                        due = occurrence?.due?.takeIf { series != null },
                        scheduled =
                            occurrence == null || series?.scheduled(occurrence.due) != false,
                        changing = changed != null,
                        earliest = series?.let { earliest(it, occurrence?.due) },
                        fixed = occurrence != null && series?.decidedAfter(occurrence.due) == true,
                    )
            }
        }
    }

    /**
     * The first date [series] can change its rule from, in front of an occurrence due on [before]
     * or else on its own: the day after the last occurrence somebody confirmed or edited, and for
     * the series on its own, not before it starts.
     */
    private fun earliest(series: Series, before: EntryDate?): EntryDate? {
        val decided =
            series.decisions
                .filter { (due, decision) ->
                    decision != Decision.Deleted && (before == null || due < before)
                }
                .keys
                .maxOrNull()
                ?.plusDays(1)
        return if (before != null) decided else listOfNotNull(decided, series.start).maxOrNull()
    }

    /** Where a change to the series on its own starts out: the next date it is due. */
    private fun from(series: Series): EntryDate {
        val tomorrow = today().plusDays(1)
        return maxOf(series.upcoming(tomorrow) ?: tomorrow, earliest(series, null)!!)
    }

    /**
     * Chooses [kind]. A transfer goes to somebody in particular, so until the user has chosen the
     * beneficiaries, a transfer starts with none and the other kinds with the defaults (EN-7). A
     * transfer without a description gets a generic one, which goes again with the transfer.
     */
    fun kind(kind: Kind) =
        mutable.update {
            val beneficiaries =
                when {
                    beneficiariesChosen -> it.beneficiaries
                    kind == Kind.TRANSFER -> emptySet()
                    else -> defaults
                }
            val description =
                when {
                    kind == Kind.TRANSFER && it.description.isBlank() -> transfer
                    kind != Kind.TRANSFER && it.description == transfer -> ""
                    else -> it.description
                }
            it.copy(kind = kind, beneficiaries = beneficiaries, description = description)
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
     * What saving [state] would do to the series beyond the entry itself, [following] the answer to
     * whether a change goes for the rest of it; null if it leaves the series as it is.
     */
    fun preview(state: EntryEditorState, following: Boolean = false): Preview? {
        val group = loaded ?: return null
        val series = series ?: return null
        val by = me ?: return null
        val written = written(state) ?: return null
        val placeholder = EventId.occurrence(series.id, state.date)
        val events = plan(state, written, following, by, Instant.EPOCH) { placeholder }
        if (events.none { it is Event.SeriesAmended || it is Event.SeriesStopped }) return null
        val after =
            try {
                events.fold(group, Group::plus).series[series.id]
            } catch (_: Malformed) {
                null
            } ?: return null
        val except = setOfNotNull(occurrence?.due, state.date.takeIf { state.amending })
        val pause = events.filterIsInstance<Event.SeriesStopped>().firstOrNull { it.until != null }
        val skipped =
            pause?.let { stop ->
                generateSequence(series.nextDue(stop.from)) { series.nextDue(it.plusDays(1)) }
                    .takeWhile { it < stop.until!! }
                    .toList()
            }
        val next =
            if (state.changing) maxOf(state.date, today().plusDays(1)) else state.date.plusDays(1)
        return Preview(
            impact = series.impact(after, today(), except),
            next = after.upcoming(next),
            skipped = skipped.orEmpty(),
        )
    }

    /**
     * Saves the entry. A change to an occurrence that could go for the rest of the series as well
     * asks first; [following] is the answer (RE-6). Its date goes for this one only, so a change to
     * the date alone does not ask.
     */
    fun save(following: Boolean? = null) {
        val state = mutable.value
        if (!state.canSave) return
        val written = written(state) ?: return
        val occurrence = occurrence
        val recurrence = state.recurrence
        if (
            occurrence != null &&
                following == null &&
                recurrence != null &&
                recurrence == state.was &&
                Template.of(written) != Template.of(occurrence.entry)
        ) {
            mutable.update { it.copy(asking = true) }
            return
        }
        mutable.update { it.copy(asking = false, saving = true, failed = false) }
        viewModelScope.launch {
            val stored =
                withContext(Dispatchers.IO) {
                    val by = repository.actingAs(group) ?: return@withContext false
                    try {
                        // One moment for all, which tells the history they are one step (HI-2).
                        plan(state, written, following == true, by, now(), ::event)
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

    /** The entry as typed; null while it cannot be saved. */
    private fun written(state: EntryEditorState): Entry? =
        Entry(
            id = id,
            kind = state.kind,
            description = state.description.trim(),
            date = state.date,
            amount = state.amount ?: return null,
            payers = state.payerSide ?: return null,
            beneficiaries = state.beneficiarySide ?: return null,
            note = state.source?.note,
            attachments = state.source?.attachments,
        )

    /** What saving [written] as [state] has it writes, by [by] at [at]. */
    private fun plan(
        state: EntryEditorState,
        written: Entry,
        following: Boolean,
        by: MemberId,
        at: Instant,
        ids: () -> EventId,
    ): List<Event> {
        val occurrence = occurrence
        val recurrence = state.recurrence
        return when {
            state.changing -> listOfNotNull(amended(state.date, written, recurrence!!, by, at, ids))
            occurrence != null ->
                when {
                    state.amending -> restarted(occurrence.due, written, recurrence!!, by, at, ids)
                    else -> changed(occurrence, written, state, following, by, at, ids)
                }
            recurrence != null -> started(written, recurrence, state.editing, by, at)
            else -> listOfNotNull(entry(written, state, by, at))
        }
    }

    /** A plain entry, created or changed; null if nothing changed. */
    private fun entry(written: Entry, state: EntryEditorState, by: MemberId, at: Instant): Event? {
        val source = state.source
        if (!state.editing || source == null) return Event.EntryCreated(event(), by, at, written)
        val changes = EntryChanges.between(source, written)
        return if (changes.isEmpty) null else Event.EntryUpdated(event(), by, at, id, changes)
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
        at: Instant,
    ): List<Event> {
        val series = SeriesId.parse(identifier())
        val start = written.date
        val template = Template.of(written)
        val segment = Segment(recurrence.interval, recurrence.mode, template)
        val produced = recurrence.interval.dates(start.value, start.value).any()
        val first = EventId.occurrence(series, start)
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

    /** The series as [written] and [recurrence] from [from] on, with no occurrence (RE-6). */
    private fun amended(
        from: EntryDate,
        written: Entry,
        recurrence: Recurrence,
        by: MemberId,
        at: Instant,
        ids: () -> EventId,
    ): Event? {
        val series = checkNotNull(series)
        val segment = Segment(recurrence.interval, recurrence.mode, Template.of(written))
        if (series.segment(from) == segment) return null
        return Event.SeriesAmended(ids(), by, at, series.id, from, segment)
    }

    /**
     * The occurrence due on [due] as [written] and the first of its series' new [recurrence], which
     * runs from the occurrence's date on (RE-6). The old rule's dates between the two make way for
     * it in a pause. The occurrence's old due date goes, a confirmation there included, unless the
     * new rule falls on it too, which makes it a date of the new rule.
     */
    private fun restarted(
        due: EntryDate,
        written: Entry,
        recurrence: Recurrence,
        by: MemberId,
        at: Instant,
        ids: () -> EventId,
    ): List<Event> {
        val series = checkNotNull(series)
        val from = written.date
        val template = Template.of(written)
        val segment = Segment(recurrence.interval, recurrence.mode, template)
        val amended = Event.SeriesAmended(ids(), by, at, series.id, from, segment)
        var after = series.from(from, segment).restored()
        val pause =
            series
                .nextDue(due.plusDays(1))
                ?.takeIf { from > due && it < from }
                ?.let { skipped ->
                    after = after.stopped(Stop(skipped, from))
                    Event.SeriesStopped(ids(), by, at, series.id, skipped, from)
                }
        val first = decision(after, from, written, by, at)
        val old = EventId.occurrence(series.id, due)
        val left =
            when {
                from == due -> null
                from < due && after.scheduled(due) ->
                    if (series.decisions[due] is Decision.Amended)
                        Event.OccurrenceConfirmed(old, by, at, series.id, due)
                    else null
                after.occurrences(maxOf(today(), due)).any { it.due == due } ->
                    Event.OccurrenceDeleted(old, by, at, series.id, due)
                else -> null
            }
        return listOfNotNull(amended, pause, first, left)
    }

    /**
     * [occurrence] as [written], and its series from there on — [following] the occurrence when it
     * keeps its rule, stopping after it when it no longer repeats (RE-6, RE-7). A series fixed by a
     * later decision only stops, as amending it would rewrite what somebody confirmed or edited.
     */
    private fun changed(
        occurrence: Occurrence,
        written: Entry,
        state: EntryEditorState,
        following: Boolean,
        by: MemberId,
        at: Instant,
        ids: () -> EventId,
    ): List<Event> {
        val series = checkNotNull(series)
        val due = occurrence.due
        val recurrence = state.recurrence
        val template = Template.of(written)
        val (change, after) =
            when {
                recurrence == null ->
                    if (state.was == null) null to series
                    else {
                        val next = due.plusDays(1)
                        Event.SeriesStopped(ids(), by, at, series.id, next) to
                            series.stopped(Stop(next))
                    }
                !state.fixed && following && template != series.segment(due)?.template -> {
                    val segment = Segment(recurrence.interval, recurrence.mode, template)
                    Event.SeriesAmended(ids(), by, at, series.id, due, segment) to
                        series.from(due, segment).restored()
                }
                else -> null to series
            }
        return listOfNotNull(change, decision(after, due, written, by, at))
    }

    /**
     * What [after] needs on [due] to have [written] there: nothing where it produces it as written,
     * a confirmation where it only suggests it, and else the entry itself.
     */
    private fun decision(
        after: Series,
        due: EntryDate,
        written: Entry,
        by: MemberId,
        at: Instant,
    ): Event? {
        val produced = after.occurrences(maxOf(today(), due)).find { it.due == due }
        val decided = EventId.occurrence(after.id, due)
        return when {
            produced?.entry != written.copy(id = EntryId.occurrence(after.id, due)) ->
                Event.OccurrenceAmended(
                    decided,
                    by,
                    at,
                    after.id,
                    due,
                    written.date,
                    Template.of(written),
                )
            produced.pending -> Event.OccurrenceConfirmed(decided, by, at, after.id, due)
            else -> null
        }
    }

    private fun event() = EventId.parse(identifier())

    private fun Set<MemberId>.toggled(member: MemberId) =
        if (member in this) this - member else this + member
}

/**
 * What a change does to its series beyond the entry being edited (RE-6, RE-7).
 *
 * @property next the first date due after the entry, or with a series changed on its own, after
 *   today.
 * @property skipped the old rule's dates a changed rule pauses, between the occurrence and its new
 *   date.
 */
data class Preview(val impact: Impact, val next: EntryDate?, val skipped: List<EntryDate>)

private fun EntryDate.plusDays(days: Long) = EntryDate.of(value.plusDays(days))
