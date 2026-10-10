package eu.teele.neugesplitter.domain.recurrence

import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.SeriesId
import java.time.LocalDate

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
 * Days on which a series produces nothing: from [from] up to [until], which is the day it
 * continues, or for good without one (RE-7).
 */
data class Stop(val from: EntryDate, val until: EntryDate? = null) {

    operator fun contains(date: EntryDate): Boolean =
        from <= date && (until == null || date < until)

    /** Whether this and [other] overlap or meet, so that together they are one. */
    internal fun touches(other: Stop): Boolean = from.value <= other.end && other.from.value <= end

    internal fun overlaps(other: Stop): Boolean = from.value < other.end && other.from.value < end

    private val end: LocalDate
        get() = until?.value ?: LocalDate.MAX
}

/**
 * A recurring series, as three layers (format-spec 4.2): the schedule its segments make, the stops
 * that take days out of it, and the decisions on single due dates, which outrank both.
 *
 * Occurrences are not stored but derived (event-model 6), so this holds only what the log said.
 */
data class Series(
    val id: SeriesId,
    /** In log order. */
    val segments: List<Pair<EntryDate, Segment>>,
    /** Apart from one another and in order of date. */
    val stops: List<Stop> = emptyList(),
    val decisions: Map<EntryDate, Decision> = emptyMap(),
    val deleted: Boolean = false,
    /** Due dates decided since the series was last deleted, whose decisions outlive it. */
    val sinceDeletion: Set<EntryDate> = emptySet(),
) {

    /** The first day of the schedule. */
    val start: EntryDate
        get() = segments.minOf { it.first }

    /** This series following [segment] from [from] (RE-6). */
    fun from(from: EntryDate, segment: Segment): Series =
        copy(segments = segments + (from to segment))

    /** This series stopped on the days of [stop] as well, which joins any stop it touches. */
    fun stopped(stop: Stop): Series {
        val (joined, apart) = stops.partition { it.touches(stop) }
        val all = joined + stop
        val until = if (all.any { it.until == null }) null else all.maxOf { it.until!! }
        return copy(stops = (apart + Stop(all.minOf { it.from }, until)).sortedBy { it.from })
    }

    /** This series running on the days of [span] again, wherever it was stopped. */
    fun continued(span: Stop): Series =
        copy(
            stops =
                stops.flatMap { stop ->
                    if (!stop.overlaps(span)) return@flatMap listOf(stop)
                    listOfNotNull(
                        Stop(stop.from, span.from).takeIf { stop.from < span.from },
                        span.until?.let { until ->
                            Stop(until, stop.until).takeIf {
                                stop.until == null || until < stop.until
                            }
                        },
                    )
                }
        )

    fun decided(due: EntryDate, decision: Decision): Series =
        copy(decisions = decisions + (due to decision), sinceDeletion = sinceDeletion + due)

    fun removed(): Series = copy(deleted = true, sinceDeletion = emptySet())

    fun restored(): Series = copy(deleted = false)

    /** The schedule on [on], stops or not: null only before it starts (RE-6). */
    fun segment(on: EntryDate): Segment? =
        // A stable sort, as in dues: of two segments from the same date the later in the log wins.
        segments.sortedBy { it.first }.lastOrNull { it.first <= on }?.second

    /** The schedule on [today], or on its first day if that is still ahead. */
    fun current(today: EntryDate): Segment = segment(maxOf(today, start))!!

    /** The stop in force on [today], or else the next one ahead; null if there is none. */
    fun stop(today: EntryDate): Stop? = stops.firstOrNull { it.until == null || today < it.until }

    /** Whether somebody confirmed or edited an occurrence due after [date] (RE-6). */
    fun decidedAfter(date: EntryDate): Boolean =
        decisions.any { (due, decision) -> due > date && decision != Decision.Deleted }

    /**
     * The first day on or after [date] the schedule makes due, stops or not; null if none in a
     * year.
     */
    fun nextDue(date: EntryDate): EntryDate? =
        dues(EntryDate.of(date.value.plusYears(1))).keys.filter { it >= date }.minOrNull()

    /**
     * Every occurrence due on or before [today], the installation's local date (RE-14), in order of
     * due date. A confirmed or amended one stands even where nothing is due any more (format-spec
     * 4.2).
     */
    fun occurrences(today: EntryDate): List<Occurrence> {
        val calculated =
            if (deleted) emptyMap() else dues(today).filterKeys { due -> stops.none { due in it } }
        val standing =
            decisions.mapNotNull { (due, decision) ->
                val kept = !deleted || due in sinceDeletion
                if (!kept || due > today || due in calculated) return@mapNotNull null
                when (decision) {
                    Decision.Confirmed -> segment(due)?.let { confirmed(due, it) }
                    Decision.Deleted -> null
                    is Decision.Amended -> amended(due, decision)
                }
            }
        return (calculated.mapNotNull { (due, segment) -> occurrence(due, segment) } + standing)
            .sortedBy { it.due }
    }

    /** Each date the schedule makes due by [today], with the segment in force on it. */
    private fun dues(today: EntryDate): Map<EntryDate, Segment> {
        // A stable sort, so that of two segments from the same date the later in the log is last.
        val ordered = segments.sortedBy { it.first }
        return ordered
            .flatMapIndexed { index, (from, segment) ->
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
            Decision.Confirmed -> confirmed(due, segment)
            Decision.Deleted -> null
            is Decision.Amended -> amended(due, decision)
        }
    }

    private fun confirmed(due: EntryDate, segment: Segment) =
        Occurrence(id, due, segment.template.on(EntryId.occurrence(id, due), due), false)

    private fun amended(due: EntryDate, decision: Decision.Amended) =
        Occurrence(
            this.id,
            due,
            decision.entry.on(EntryId.occurrence(id, due), decision.date),
            false,
        )
}
