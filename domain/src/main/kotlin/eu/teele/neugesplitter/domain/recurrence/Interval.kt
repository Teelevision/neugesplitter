package eu.teele.neugesplitter.domain.recurrence

import eu.teele.neugesplitter.domain.Malformed
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** How often a segment falls due (format-spec 4.2, RE-2). */
sealed interface Interval {

    /** The dates this produces on or after [from] and up to [through], in order. */
    fun dates(from: LocalDate, through: LocalDate): Sequence<LocalDate>

    /**
     * A day by which this has produced a date on or after [date], counting from [date] or from any
     * day before it.
     */
    fun horizon(date: LocalDate): LocalDate

    data class EveryDays(val days: Int) : Interval {
        init {
            if (days < 1) throw Malformed("everyDays must be positive, was $days")
        }

        override fun dates(from: LocalDate, through: LocalDate) =
            generateSequence(from) { it.plusDays(days.toLong()) }.takeWhile { it <= through }

        override fun horizon(date: LocalDate): LocalDate = date.plusDays(days.toLong())
    }

    data class WeeklyOn(val days: Set<DayOfWeek>) : Interval {
        init {
            if (days.isEmpty()) throw Malformed("weeklyOn names no day")
        }

        override fun dates(from: LocalDate, through: LocalDate) =
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { it <= through }
                .filter { it.dayOfWeek in days }

        override fun horizon(date: LocalDate): LocalDate = date.plusWeeks(1)
    }

    /**
     * On [days] of every month, or of every [months]-th one counted from the month of the start
     * (format-spec 4.2: `monthlyOn`, `everyMonths`).
     */
    data class MonthlyOn(val days: Set<Int>, val months: Int = 1) : Interval {
        init {
            if (days.isEmpty()) throw Malformed("monthlyOn names no day")
            if (days.any { it !in 1..31 }) throw Malformed("monthlyOn outside 1 to 31: $days")
            if (months < 1) throw Malformed("everyMonths must be positive, was $months")
        }

        // Clamp first, then deduplicate: the 30th and 31st are one date in February.
        override fun dates(from: LocalDate, through: LocalDate) =
            generateSequence(YearMonth.from(from)) { it.plusMonths(months.toLong()) }
                .takeWhile { it.atDay(1) <= through }
                .flatMap { month ->
                    days.map { month.atDay(minOf(it, month.lengthOfMonth())) }.sorted().distinct()
                }
                .filter { it in from..through }

        // The next month in step is at most [months] on; three days spare for months' ends.
        override fun horizon(date: LocalDate): LocalDate =
            date.plusMonths(months.toLong()).plusDays(3)
    }
}
