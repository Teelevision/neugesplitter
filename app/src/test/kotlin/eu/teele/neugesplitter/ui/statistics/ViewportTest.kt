package eu.teele.neugesplitter.ui.statistics

import eu.teele.neugesplitter.domain.statistics.Granularity
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportTest {

    private val today = LocalDate.of(2026, 8, 31)
    private val first = LocalDate.of(2024, 1, 1)

    @Test
    fun `initial viewport ends at the end of today`() {
        val viewport = Viewport(today, first, 30.0)

        assertEquals(today.toEpochDay() + 1.0, viewport.last)
        assertEquals(viewport.last, viewport.end)
    }

    @Test
    fun `panning cannot go into the future or before the first entry`() {
        val viewport = Viewport(today, first, 30.0)

        viewport.pan(10.0)
        assertEquals(viewport.last, viewport.end)

        viewport.pan(-100_000.0)
        assertTrue(viewport.start >= first.toEpochDay())
        assertEquals(first.toEpochDay().toDouble(), viewport.start)
    }

    @Test
    fun `zoom clamps to the shortest and longest available stretches`() {
        val viewport = Viewport(today, LocalDate.of(2010, 1, 1), 30.0)

        viewport.zoom(1_000.0, 0.5)
        assertEquals(SHORTEST, viewport.days)
        viewport.zoom(0.001, 0.5)
        assertEquals(viewport.longest, viewport.days)
    }

    @Test
    fun `show resets the end and current and shows reflect the viewport`() {
        val viewport = Viewport(today, first, 30.0)

        assertTrue(viewport.current)
        assertTrue(viewport.shows(30.0))
        assertFalse(viewport.shows(60.0))

        viewport.pan(-30.0)
        assertFalse(viewport.current)
        assertFalse(viewport.shows(30.0))

        viewport.show(60.0)
        assertEquals(viewport.last, viewport.end)
        assertTrue(viewport.current)
        assertTrue(viewport.shows(60.0))
    }

    @Test
    fun `zooming from a month to a year updates granularity from day to week`() {
        val viewport = Viewport(today, first, 30.0)
        assertEquals(Granularity.DAY, viewport.granularity)

        viewport.zoom(30.0 / 365.0, 0.5)

        assertEquals(365.0, viewport.days)
        assertEquals(Granularity.WEEK, viewport.granularity)
    }
}
