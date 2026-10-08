package eu.teele.neugesplitter.sync.writer

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [action] once changes have stopped for [quiet], and at the latest [bound] after the first of
 * them, so that a burst of entries goes up as one object and a steady trickle cannot hold an upload
 * back indefinitely (TR-14, SY-14). Changes made while [action] runs start the next wait.
 *
 * [action] runs in [scope], one at a time, and is expected to handle its own failures.
 */
class Debounce(
    scope: CoroutineScope,
    private val quiet: Duration,
    private val bound: Duration,
    private val time: TimeSource = TimeSource.Monotonic,
    private val action: suspend () -> Unit,
) {

    /** True for a run that does not wait. */
    private val signals = Channel<Boolean>(Channel.UNLIMITED)

    init {
        require(quiet.isPositive() && quiet <= bound) { "the quiet period lies within the bound" }
        scope.launch {
            for (urgent in signals) {
                if (!urgent) settle()
                action()
            }
        }
    }

    /** Something changed that is to be written. */
    fun changed() {
        signals.trySend(false)
    }

    /** Runs without waiting any longer, as a manual sync does (SY-9). */
    fun now() {
        signals.trySend(true)
    }

    /** Waits until changes stop, the bound passes, or a run is wanted now. */
    private suspend fun settle() {
        val start = time.markNow()
        while (true) {
            val left = bound - start.elapsedNow()
            if (!left.isPositive()) return
            val urgent = withTimeoutOrNull(minOf(quiet, left)) { signals.receive() } ?: return
            if (urgent) return
        }
    }
}
