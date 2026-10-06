package eu.teele.neugesplitter.domain.recurrence

import eu.teele.neugesplitter.domain.Malformed
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** How often a segment falls due (format-spec 4.2, RE-2). */
sealed interface Interval {

    /** The dates this produces on or after [from] and up to [through], in order. */
    fun dates(from: LocalDate, through: LocalDate): Sequence<LocalDate>

    data class EveryDays(val days: Int) : Interval {
        init {
            if (days < 1) throw Malformed("everyDays must be positive, was $days")
        }

        override fun dates(from: LocalDate, through: LocalDate) =
            generateSequence(from) { it.plusDays(days.toLong()) }.takeWhile { it <= through }
    }

    data class WeeklyOn(val days: Set<DayOfWeek>) : Interval {
        init {
            if (days.isEmpty()) throw Malformed("weeklyOn names no day")
        }

        override fun dates(from: LocalDate, through: LocalDate) =
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { it <= through }
                .filter { it.dayOfWeek in days }
    }

    data class MonthlyOn(val days: Set<Int>) : Interval {
        init {
            if (days.isEmpty()) throw Malformed("monthlyOn names no day")
            if (days.any { it !in 1..31 }) throw Malformed("monthlyOn outside 1 to 31: $days")
        }

        // Clamp first, then deduplicate: the 30th and 31st are one date in February.
        override fun dates(from: LocalDate, through: LocalDate) =
            generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
                .takeWhile { it.atDay(1) <= through }
                .flatMap { month ->
                    days.map { month.atDay(minOf(it, month.lengthOfMonth())) }.sorted().distinct()
                }
                .filter { it in from..through }
    }
}
