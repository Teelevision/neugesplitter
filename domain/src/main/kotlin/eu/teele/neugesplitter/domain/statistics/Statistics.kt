package eu.teele.neugesplitter.domain.statistics

import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** How a time axis is divided (BA-15). */
enum class Granularity {
    DAY,
    WEEK,
    MONTH;

    /** The first day of the period [date] falls in; a week begins on [firstDay]. */
    fun start(date: LocalDate, firstDay: DayOfWeek): LocalDate =
        when (this) {
            DAY -> date
            WEEK -> date.with(TemporalAdjusters.previousOrSame(firstDay))
            MONTH -> date.withDayOfMonth(1)
        }

    /** The first day of the period after the one that begins on [start]. */
    fun next(start: LocalDate): LocalDate =
        when (this) {
            DAY -> start.plusDays(1)
            WEEK -> start.plusWeeks(1)
            MONTH -> start.plusMonths(1)
        }
}

/** Whose spending is counted: the whole group's, or one member's share of it as a beneficiary. */
sealed interface Perspective {
    data object Everyone : Perspective

    data class Share(val member: MemberId) : Perspective
}

/** Whether a member's spending is what they paid or what they had a share of. */
enum class Measure {
    PAID,
    CONSUMED,
}

/** What the averages are grouped by: weekdays, days of the month, or months of the year. */
enum class Cycle {
    WEEK,
    MONTH,
    YEAR,
}

/** A period beginning on [start], and what it ends with or adds up to. */
data class Period<T>(val start: LocalDate, val value: T)

/**
 * @property parts one per category asked for, in that order; what they leave of [total] is [rest].
 */
data class Spending(val total: Amount, val parts: List<Amount>) {
    val rest: Amount
        get() = total - Amount.sum(parts)
}

/**
 * One position of a cycle over every cycle sampled: a weekday counted from the first day of the
 * week, a day of the month, or a month, each from 1.
 */
data class Position(
    val index: Int,
    val mean: Amount,
    val min: Amount,
    val max: Amount,
    val samples: Int,
)

/**
 * What a group's charts show, as of [today] (BA-8).
 *
 * Counted are the entries and occurrences that are in the balances and are dated by [today]: a
 * pending suggestion is not, nor is anything dated later. Spending is expenses less income; a
 * transfer moves money between members and is not spending (event-model 3).
 */
class Statistics(val group: Group, today: EntryDate) {

    val today: LocalDate = today.value

    /** Oldest first. */
    val entries: List<Entry> =
        (group.entries.values + group.occurrences(today).filterNot { it.pending }.map { it.entry })
            .filter { it.date <= today }
            .sortedBy { it.date }

    /** The date of the earliest entry; null if there is none. */
    val first: LocalDate? = entries.firstOrNull()?.date?.value

    /** The balances as they stand, as they are shown and settled (BA-2). */
    val settlement: Map<MemberId, Amount> = group.settlement(today)

    /**
     * The balance of each of [members] at the end of every period from the one [from] falls in to
     * the one [until] does, in 10⁻⁸ units as [Group.balances] has them (BA-9).
     */
    fun balances(
        members: Collection<MemberId>,
        from: LocalDate,
        until: LocalDate,
        granularity: Granularity,
        firstDay: DayOfWeek,
    ): List<Period<Map<MemberId, Amount>>> {
        val running = members.associateWithTo(LinkedHashMap()) { Amount.ZERO }
        val periods = mutableListOf<Period<Map<MemberId, Amount>>>()
        var index = 0
        for (start in periods(from, until, granularity, firstDay)) {
            val next = granularity.next(start)
            while (index < entries.size && entries[index].date.value < next) {
                for ((member, contribution) in entries[index++].contributions()) {
                    running.computeIfPresent(member) { _, sum -> sum + contribution }
                }
            }
            periods += Period(start, running.toMap())
        }
        return periods
    }

    /**
     * What was spent in every period from the one [from] falls in to the one [until] does, and how
     * much of it in each of [categories]; null stands for no category (BA-10).
     */
    fun spending(
        perspective: Perspective,
        from: LocalDate,
        until: LocalDate,
        granularity: Granularity,
        firstDay: DayOfWeek,
        categories: List<CategoryId?> = emptyList(),
    ): List<Period<Spending>> {
        val starts = periods(from, until, granularity, firstDay)
        if (starts.isEmpty()) return emptyList()
        val totals = Array(starts.size) { Amount.ZERO }
        val parts = Array(starts.size) { Array(categories.size) { Amount.ZERO } }
        val end = granularity.next(starts.last())
        for (entry in entries) {
            val date = entry.date.value
            if (date < starts.first() || date >= end) continue
            val spent = spent(entry, perspective) ?: continue
            val period = starts.binarySearch(granularity.start(date, firstDay))
            totals[period] += spent
            val category = categories.indexOf(entry.categoryId)
            if (category >= 0) parts[period][category] += spent
        }
        return starts.mapIndexed { i, start ->
            Period(start, Spending(totals[i], parts[i].toList()))
        }
    }

    /**
     * The expenses or the income from [from] to [until], both included, per category, largest first
     * and none that comes to nothing; null stands for no category (BA-11).
     */
    fun categories(
        kind: Kind,
        perspective: Perspective,
        from: LocalDate,
        until: LocalDate,
    ): List<Pair<CategoryId?, Amount>> {
        require(kind != Kind.TRANSFER) { "a transfer is not spending" }
        val sums = LinkedHashMap<CategoryId?, Amount>()
        for (entry in between(from, until)) {
            if (entry.kind != kind) continue
            val amount = share(entry, perspective) ?: continue
            sums[entry.categoryId] = (sums[entry.categoryId] ?: Amount.ZERO) + amount
        }
        return sums.filterValues { it.isPositive }.toList().sortedByDescending { it.second }
    }

    /**
     * The expenses from [from] to [until], both included, per category and member, by what each
     * member paid or had a share of; null stands for no category (BA-12).
     */
    fun matrix(
        measure: Measure,
        from: LocalDate,
        until: LocalDate,
    ): Map<CategoryId?, Map<MemberId, Amount>> {
        val cells = LinkedHashMap<CategoryId?, MutableMap<MemberId, Amount>>()
        for (entry in between(from, until)) {
            if (entry.kind != Kind.EXPENSE) continue
            val side = if (measure == Measure.PAID) entry.payers else entry.beneficiaries
            val row = cells.getOrPut(entry.categoryId) { LinkedHashMap() }
            for ((member, part) in side.parts(entry.amount)) {
                row[member] = (row[member] ?: Amount.ZERO) + part
            }
        }
        return cells
    }

    /**
     * What was spent on average at each position of [cycle], over the last [years] years or since
     * the first entry if that is later. A cycle under way counts with the days that have passed,
     * and a day of the month only over the months that have it (BA-13).
     */
    fun averages(
        cycle: Cycle,
        years: Int,
        perspective: Perspective,
        firstDay: DayOfWeek,
    ): List<Position> {
        require(years > 0) { "averages are over at least one year" }
        val first = first ?: return emptyList()
        val start = maxOf(first, today.minusYears(years.toLong()).plusDays(1))
        val daily = HashMap<LocalDate, Amount>()
        for (entry in between(start, today)) {
            val spent = spent(entry, perspective) ?: continue
            daily[entry.date.value] = (daily[entry.date.value] ?: Amount.ZERO) + spent
        }
        val samples = sortedMapOf<Int, MutableList<Amount>>()
        if (cycle == Cycle.YEAR) {
            val months = LinkedHashMap<YearMonth, Amount>()
            for (date in start.datesUntil(today.plusDays(1))) {
                val month = YearMonth.from(date)
                months[month] = (months[month] ?: Amount.ZERO) + (daily[date] ?: Amount.ZERO)
            }
            for ((month, sum) in months) samples.getOrPut(month.monthValue) { mutableListOf() } +=
                sum
        } else {
            for (date in start.datesUntil(today.plusDays(1))) {
                val index =
                    if (cycle == Cycle.WEEK) (date.dayOfWeek.value - firstDay.value + 7) % 7
                    else date.dayOfMonth
                samples.getOrPut(index) { mutableListOf() } += daily[date] ?: Amount.ZERO
            }
        }
        return samples.map { (index, values) ->
            Position(
                index,
                Amount.ofUnits(Amount.sum(values).units / values.size),
                values.min(),
                values.max(),
                values.size,
            )
        }
    }

    /**
     * The income per category on one side and the expenses on the other from [from] to [until],
     * both included, with no more than [limit] nodes a side besides the balance (BA-14).
     */
    fun flows(
        perspective: Perspective,
        from: LocalDate,
        until: LocalDate,
        limit: Int = Int.MAX_VALUE,
    ): Flows =
        Flows.of(
            categories(Kind.INCOME, perspective, from, until),
            categories(Kind.EXPENSE, perspective, from, until),
            limit,
        )

    private fun between(from: LocalDate, until: LocalDate) =
        entries.asSequence().filter { it.date.value in from..until }

    private fun periods(
        from: LocalDate,
        until: LocalDate,
        granularity: Granularity,
        firstDay: DayOfWeek,
    ): List<LocalDate> =
        generateSequence(granularity.start(from, firstDay), granularity::next)
            .takeWhile { it <= until }
            .toList()

    /** What [entry] spent as [perspective] sees it, less for an income; null for a transfer. */
    private fun spent(entry: Entry, perspective: Perspective): Amount? {
        if (entry.kind == Kind.TRANSFER) return null
        val amount = share(entry, perspective) ?: return null
        return if (entry.kind == Kind.INCOME) -amount else amount
    }

    private fun share(entry: Entry, perspective: Perspective): Amount? =
        when (perspective) {
            Perspective.Everyone -> entry.amount
            is Perspective.Share -> entry.beneficiaries.parts(entry.amount)[perspective.member]
        }
}
