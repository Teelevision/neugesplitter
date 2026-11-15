package eu.teele.neugesplitter.ui.statistics

import androidx.compose.ui.geometry.Rect
import eu.teele.neugesplitter.domain.statistics.Granularity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AxisTest {

    @Test
    fun `granularity switches at its plain and hysteresis thresholds`() {
        assertEquals(Granularity.DAY, granularity(100.0))
        assertEquals(Granularity.WEEK, granularity(101.0))
        assertEquals(Granularity.WEEK, granularity(800.0))
        assertEquals(Granularity.MONTH, granularity(801.0))

        assertEquals(Granularity.DAY, granularity(110.0, Granularity.DAY))
        assertEquals(Granularity.WEEK, granularity(120.0, Granularity.DAY))
        assertEquals(Granularity.DAY, granularity(115.0, Granularity.DAY))
        assertEquals(Granularity.WEEK, granularity(116.0, Granularity.DAY))

        assertEquals(Granularity.WEEK, granularity(90.0, Granularity.WEEK))
        assertEquals(Granularity.WEEK, granularity(900.0, Granularity.WEEK))
        assertEquals(Granularity.DAY, granularity(80.0, Granularity.WEEK))
        assertEquals(Granularity.MONTH, granularity(930.0, Granularity.WEEK))
        assertEquals(Granularity.DAY, granularity(84.0, Granularity.WEEK))
        assertEquals(Granularity.WEEK, granularity(85.0, Granularity.WEEK))
        assertEquals(Granularity.WEEK, granularity(920.0, Granularity.WEEK))
        assertEquals(Granularity.MONTH, granularity(921.0, Granularity.WEEK))

        assertEquals(Granularity.MONTH, granularity(700.0, Granularity.MONTH))
        assertEquals(Granularity.WEEK, granularity(670.0, Granularity.MONTH))
        assertEquals(Granularity.MONTH, granularity(680.0, Granularity.MONTH))
        assertEquals(Granularity.WEEK, granularity(679.0, Granularity.MONTH))
    }

    @Test
    fun `ticks include zero round out the bounds and use a nice step`() {
        for ((minimum, maximum) in listOf(0.0 to 0.0, -13.0 to -2.0, -7.0 to 19.0)) {
            val result = ticks(minimum, maximum)
            assertTrue(0.0 in result.values)
            assertTrue(result.low <= minimum)
            assertTrue(result.high >= maximum)

            val exponent = floor(log10(result.step))
            val mantissa = result.step / 10.0.pow(exponent)
            assertTrue(
                listOf(1.0, 2.0, 2.5, 5.0).any { abs(mantissa - it) < 1e-9 },
                "Unexpected tick step ${result.step} (mantissa $mantissa)",
            )
        }
    }

    @Test
    fun `span windows include both ends and all starts at the first available date`() {
        val today = LocalDate.of(2026, 8, 31)
        val first = LocalDate.of(2024, 2, 10)

        assertEquals(Window(LocalDate.of(2026, 8, 1), today), Span.MONTH.window(today, first))
        assertEquals(
            Window(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)),
            Span.MONTH.window(today, first, back = 1),
        )
        assertEquals(Window(LocalDate.of(2025, 9, 1), today), Span.YEAR.window(today, first))
        assertEquals(Window(first, today), Span.ALL.window(today, first, back = 4))
        assertEquals(Window(today, today), Span.ALL.window(today, null, back = 4))
        assertEquals(31L, Span.MONTH.window(today, first).days)
    }

    @Test
    fun `date ticks use weekly monthly quarterly and yearly spacing within the range`() {
        val weeklyFrom = LocalDate.of(2026, 1, 1)
        val (weeklyLabel, weekly) =
            dateTicks(weeklyFrom, LocalDate.of(2026, 1, 31), DayOfWeek.MONDAY)
        assertEquals(DateLabel.DAY, weeklyLabel)
        assertTrue(weekly.isNotEmpty())
        assertTrue(weekly.all { it.dayOfWeek == DayOfWeek.MONDAY })
        assertTrue(weekly.zipWithNext().all { (a, b) -> ChronoUnit.DAYS.between(a, b) == 7L })

        val (monthlyLabel, monthly) =
            dateTicks(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 5, 15), DayOfWeek.MONDAY)
        assertEquals(DateLabel.MONTH, monthlyLabel)
        assertTrue(monthly.isNotEmpty())
        assertTrue(monthly.all { it.dayOfMonth == 1 })
        assertTrue(monthly.zipWithNext().all { (a, b) -> ChronoUnit.MONTHS.between(a, b) == 1L })

        val (quarterlyLabel, quarterly) =
            dateTicks(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 2, 5), DayOfWeek.MONDAY)
        assertEquals(DateLabel.MONTH_YEAR, quarterlyLabel)
        assertTrue(quarterly.isNotEmpty())
        assertTrue(quarterly.all { it.dayOfMonth == 1 && (it.monthValue - 1) % 3 == 0 })
        assertTrue(quarterly.zipWithNext().all { (a, b) -> ChronoUnit.MONTHS.between(a, b) == 3L })

        val from = LocalDate.of(2015, 1, 1)
        val until = LocalDate.of(2025, 1, 1)
        val (yearlyLabel, yearly) = dateTicks(from, until, DayOfWeek.MONDAY)
        assertEquals(DateLabel.YEAR, yearlyLabel)
        assertTrue(yearly.isNotEmpty())
        assertTrue(yearly.all { it.monthValue == 1 && it.dayOfMonth == 1 })
        assertTrue(yearly.zipWithNext().all { (a, b) -> ChronoUnit.YEARS.between(a, b) == 2L })

        for ((rangeFrom, rangeUntil, dates) in
            listOf(
                Triple(weeklyFrom, LocalDate.of(2026, 1, 31), weekly),
                Triple(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 5, 15), monthly),
                Triple(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 2, 5), quarterly),
                Triple(from, until, yearly),
            )) {
            assertTrue(dates.all { it >= rangeFrom && it <= rangeUntil })
        }
    }

    @Test
    fun `squarify preserves proportional areas bounds and non-overlap`() {
        val bounds = Rect(10f, 20f, 410f, 220f)
        val values = listOf(6.0, 3.0, 2.0, 1.0)
        val rects = squarify(values, bounds)

        assertEquals(values.size, rects.size)
        val totalValue = values.sum()
        val boundsArea = bounds.width * bounds.height
        for (index in rects.indices) {
            val rect = rects[index]
            assertTrue(rect.left >= bounds.left - 0.01f && rect.top >= bounds.top - 0.01f)
            assertTrue(rect.right <= bounds.right + 0.01f && rect.bottom <= bounds.bottom + 0.01f)
            val expected = boundsArea * values[index] / totalValue
            val actual = rect.width * rect.height
            assertTrue(abs(actual / expected - 1.0) < 1e-3, "Area mismatch at index $index")
        }
        for (left in rects.indices) {
            for (right in left + 1 until rects.size) {
                assertEquals(0.0, intersectionArea(rects[left], rects[right]), 0.01)
            }
        }
        assertEquals(emptyList(), squarify(emptyList(), bounds))
        assertEquals(3, squarify(listOf(6.0, 0.0, 4.0), bounds).size)
        assertEquals(2, squarify(listOf(0.0, 0.0), bounds).size)
    }

    private fun intersectionArea(left: Rect, right: Rect): Double =
        (minOf(left.right, right.right) - maxOf(left.left, right.left)).coerceAtLeast(0f) *
            (minOf(left.bottom, right.bottom) - maxOf(left.top, right.top))
                .coerceAtLeast(0f)
                .toDouble()
}
