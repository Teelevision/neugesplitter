package eu.teele.neugesplitter.domain.recurrence

import eu.teele.neugesplitter.domain.Malformed
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IntervalTest {

    private fun Interval.between(from: String, through: String) =
        dates(LocalDate.parse(from), LocalDate.parse(through)).map { it.toString() }.toList()

    @Test
    fun `every n days counts from the first day itself (format-spec 4_2)`() {
        assertEquals(
            listOf("2026-08-01", "2026-08-15", "2026-08-29"),
            Interval.EveryDays(14).between("2026-08-01", "2026-09-11"),
        )
    }

    @Test
    fun `weekly takes several days at once (RE-2)`() {
        assertEquals(
            listOf("2026-08-03", "2026-08-06", "2026-08-10", "2026-08-13"),
            Interval.WeeklyOn(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
                .between("2026-08-01", "2026-08-14"),
        )
    }

    @Test
    fun `monthly takes several days at once and skips those before the first day (RE-2)`() {
        assertEquals(
            listOf("2026-08-15", "2026-09-01", "2026-09-15"),
            Interval.MonthlyOn(setOf(15, 1)).between("2026-08-02", "2026-09-30"),
        )
    }

    @Test
    fun `the 30th and 31st are one date in February, not two (format-spec 4_2)`() {
        assertEquals(
            listOf("2027-01-30", "2027-01-31", "2027-02-28", "2027-03-30", "2027-03-31"),
            Interval.MonthlyOn(setOf(30, 31)).between("2027-01-01", "2027-03-31"),
        )
    }

    @Test
    fun `the 31st clamps to the 29th in a leap year (format-spec 4_2)`() {
        assertEquals(
            listOf("2028-02-29"),
            Interval.MonthlyOn(setOf(31)).between("2028-02-01", "2028-02-29"),
        )
    }

    @Test
    fun `every n months counts from the first day's month and clamps as monthly does (RE-2)`() {
        assertEquals(
            listOf("2027-01-31", "2027-04-30", "2027-07-31", "2027-10-31", "2028-01-31"),
            Interval.MonthlyOn(setOf(31), 3).between("2027-01-31", "2028-01-31"),
        )
        assertEquals(
            listOf("2028-08-15", "2030-08-15"),
            Interval.MonthlyOn(setOf(15), 24).between("2028-08-01", "2030-08-31"),
        )
    }

    @Test
    fun `an interval that cannot produce dates is malformed (format-spec 4_2)`() {
        assertFailsWith<Malformed> { Interval.EveryDays(0) }
        assertFailsWith<Malformed> { Interval.WeeklyOn(emptySet()) }
        assertFailsWith<Malformed> { Interval.MonthlyOn(emptySet()) }
        assertFailsWith<Malformed> { Interval.MonthlyOn(setOf(0)) }
        assertFailsWith<Malformed> { Interval.MonthlyOn(setOf(32)) }
        assertFailsWith<Malformed> { Interval.MonthlyOn(setOf(1), 0) }
    }
}
