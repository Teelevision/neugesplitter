package eu.teele.neugesplitter.domain.recurrence

import eu.teele.neugesplitter.domain.arbDate
import eu.teele.neugesplitter.domain.arbSeries
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** RE-8 and RE-14 held over generated series: occurrences are a function of the dates alone. */
class RecurrencePropertyTest {

    @Test
    fun `no due date yields two occurrences (RE-8)`() {
        runBlocking {
            checkAll(arbSeries, arbDate) { series, today ->
                val dues = series.occurrences(today).map { it.due }
                assertEquals(dues.sorted().distinct(), dues, "dues out of order or repeated: $dues")
            }
        }
    }

    @Test
    fun `nothing is due after today, nor before the series begins unless somebody amended it (RE-14)`() {
        runBlocking {
            checkAll(arbSeries, arbDate) { series, today ->
                val start = series.segments.minOf { it.first }
                assertTrue(
                    series.occurrences(today).all {
                        it.due <= today &&
                            (it.due >= start || series.decisions[it.due] is Decision.Amended)
                    },
                    "an occurrence outside $start..$today",
                )
            }
        }
    }

    @Test
    fun `a confirmed or amended occurrence stands until an event names it (format-spec 4_2)`() {
        runBlocking {
            checkAll(arbSeries, arbDate) { series, today ->
                val standing =
                    series.decisions
                        .filter { (due, decision) ->
                            (decision is Decision.Amended ||
                                decision == Decision.Confirmed && due >= series.start) &&
                                due <= today &&
                                (!series.deleted || due in series.sinceDeletion)
                        }
                        .keys
                val dues = series.occurrences(today).map { it.due }.toSet()
                assertTrue(dues.containsAll(standing), "$standing missing from $dues")
                if (series.deleted) assertEquals(standing, dues)
            }
        }
    }

    @Test
    fun `what is due by a date does not depend on when it was worked out (RE-8, RE-14)`() {
        runBlocking {
            checkAll(arbSeries, arbDate, arbDate) { series, a, b ->
                val (earlier, later) = if (a <= b) a to b else b to a
                assertEquals(
                    series.occurrences(earlier),
                    series.occurrences(later).filter { it.due <= earlier },
                )
            }
        }
    }
}
