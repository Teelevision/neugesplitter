package eu.teele.neugesplitter.domain.recurrence

import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.SeriesId

/** What the last occurrence event for a due date said about it (format-spec 4.2). */
sealed interface Decision {
    data object Confirmed : Decision

    data object Deleted : Decision

    data class Amended(val date: EntryDate, val entry: Template) : Decision
}

/** One due date's occurrence. A pending one is offered to the user but is not in the balances. */
data class Occurrence(
    val series: SeriesId,
    val due: EntryDate,
    val entry: Entry,
    val pending: Boolean,
)

/**
 * A recurring series: its segments in log order and the decisions taken on its due dates.
 *
 * Occurrences are not stored but derived (event-model 6), so this holds only what the log said.
 */
data class Series(
    val id: SeriesId,
    val segments: List<Pair<EntryDate, Segment?>>,
    val decisions: Map<EntryDate, Decision> = emptyMap(),
    val deleted: Boolean = false,
    /** Due dates decided since the series was last deleted, whose amendments outlive it. */
    val sinceDeletion: Set<EntryDate> = emptySet(),
) {

    /** This series begins a new segment from [from]; `null` produces nothing (RE-7). */
    fun from(from: EntryDate, segment: Segment?): Series =
        copy(segments = segments + (from to segment))

    fun decided(due: EntryDate, decision: Decision): Series =
        copy(decisions = decisions + (due to decision), sinceDeletion = sinceDeletion + due)

    fun removed(): Series = copy(deleted = true, sinceDeletion = emptySet())

    fun restored(): Series = copy(deleted = false)

    /** What this series does on [date]: null before it starts and where it is ended (RE-7). */
    fun segment(on: EntryDate): Segment? =
        // A stable sort, as in dues: of two segments from the same date the later in the log wins.
        segments.sortedBy { it.first }.lastOrNull { it.first <= on }?.second

    /**
     * Where this series stops, as it stands on [today]: the first day of the stop in force, or else
     * of the next one ahead; null if none. A pause and an end are the same thing (RE-7).
     */
    fun stop(today: EntryDate): EntryDate? {
        val effective = effective()
        val dates = effective.keys.toList()
        val now = dates.indexOfLast { it <= today }
        if (now >= 0 && effective.getValue(dates[now]) == null) {
            return dates.take(now + 1).takeLastWhile { effective.getValue(it) == null }.first()
        }
        return dates.drop(now + 1).firstOrNull { effective.getValue(it) == null }
    }

    /**
     * How resuming on [today] goes on: from today, or from the stop if that is still ahead, with
     * the segment in force before the stop; null if there is no stop (RE-7).
     */
    fun resumption(today: EntryDate): Pair<EntryDate, Segment>? {
        val stop = stop(today) ?: return null
        val segment = effective().filterKeys { it < stop }.values.filterNotNull().lastOrNull()
        return segment?.let { maxOf(today, stop) to it }
    }

    /** What this series does on [today], or did last before it stopped, or does once it starts. */
    fun current(today: EntryDate): Segment? =
        segment(today)
            ?: resumption(today)?.second
            ?: effective().values.firstNotNullOfOrNull { it }

    /** Each date a segment begins on, with the one in force from it: the later in the log. */
    private fun effective(): Map<EntryDate, Segment?> = segments.sortedBy { it.first }.toMap()

    /**
     * Every occurrence due on or before [today], the installation's local date (RE-14), in order of
     * due date. An amended one stands even where nothing is due any more (format-spec 4.2).
     */
    fun occurrences(today: EntryDate): List<Occurrence> {
        val calculated = if (deleted) emptyMap() else dues(today)
        val standing =
            decisions.mapNotNull { (due, decision) ->
                val kept = !deleted || due in sinceDeletion
                if (decision is Decision.Amended && kept && due <= today && due !in calculated) {
                    amended(due, decision)
                } else null
            }
        return (calculated.mapNotNull { (due, segment) -> occurrence(due, segment) } + standing)
            .sortedBy { it.due }
    }

    /** Each date the calculation makes due by [today], with the segment in force on it. */
    private fun dues(today: EntryDate): Map<EntryDate, Segment> {
        // A stable sort, so that of two segments from the same date the later in the log is last.
        val ordered = segments.sortedBy { it.first }
        return ordered
            .flatMapIndexed { index, (from, segment) ->
                if (segment == null) return@flatMapIndexed emptyList()
                val until = ordered.getOrNull(index + 1)?.first
                segment.interval
                    .dates(from.value, today.value)
                    .map(EntryDate::of)
                    .takeWhile { until == null || it < until }
                    .map { it to segment }
                    .toList()
            }
            .toMap()
    }

    private fun occurrence(due: EntryDate, segment: Segment): Occurrence? {
        val id = EntryId.occurrence(this.id, due)
        return when (val decision = decisions[due]) {
            null ->
                Occurrence(
                    this.id,
                    due,
                    segment.template.on(id, due),
                    segment.mode == Mode.SUGGESTED,
                )
            Decision.Confirmed -> Occurrence(this.id, due, segment.template.on(id, due), false)
            Decision.Deleted -> null
            is Decision.Amended -> amended(due, decision)
        }
    }

    private fun amended(due: EntryDate, decision: Decision.Amended) =
        Occurrence(
            this.id,
            due,
            decision.entry.on(EntryId.occurrence(id, due), decision.date),
            false,
        )
}
