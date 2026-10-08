package eu.teele.neugesplitter.sync.writer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class DebounceTest {

    private var runs = 0

    private fun TestScope.debounce() =
        Debounce(backgroundScope, 2.seconds, 5.seconds, testScheduler.timeSource) { runs++ }

    @Test
    fun `a burst is written once, after it stops (SY-14)`() = runTest {
        val debounce = debounce()
        debounce.changed()
        advanceTimeBy(1.seconds)
        debounce.changed()
        advanceTimeBy(1900.milliseconds)
        assertEquals(0, runs)
        advanceTimeBy(200.milliseconds)
        assertEquals(1, runs)
        advanceTimeBy(10.seconds)
        assertEquals(1, runs)
    }

    @Test
    fun `a steady trickle is written once the bound passes (TR-14)`() = runTest {
        val debounce = debounce()
        // Changes at 0, 1.5, 3 and 4.5 seconds, never 2 apart; the bound is 5.
        repeat(3) {
            debounce.changed()
            advanceTimeBy(1500.milliseconds)
        }
        debounce.changed()
        advanceTimeBy(400.milliseconds)
        assertEquals(0, runs)
        advanceTimeBy(200.milliseconds)
        assertEquals(1, runs)
    }

    @Test
    fun `a manual sync does not wait (SY-9)`() = runTest {
        val debounce = debounce()
        debounce.changed()
        advanceTimeBy(1.seconds)
        debounce.now()
        runCurrent()
        assertEquals(1, runs)
        debounce.now()
        runCurrent()
        assertEquals(2, runs)
    }

    @Test
    fun `a change during a run waits for the next one`() = runTest {
        val debounce = debounce()
        debounce.now()
        debounce.changed()
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(2100.milliseconds)
        assertEquals(2, runs)
    }
}
