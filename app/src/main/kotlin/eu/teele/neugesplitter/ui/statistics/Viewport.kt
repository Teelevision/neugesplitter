package eu.teele.neugesplitter.ui.statistics

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import eu.teele.neugesplitter.domain.statistics.Granularity
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The stretch of time a chart shows, in days since the epoch, ending at [end] exclusive and [days]
 * long (BA-15). It pans no later than today and no earlier than the first entry, and zooms in to a
 * week and out to five years or all of it.
 */
@Stable
class Viewport(today: LocalDate, first: LocalDate?, days: Double, end: Double? = null) {

    /** The end of today. */
    val last: Double = today.toEpochDay() + 1.0

    private val earliest: Double = (first ?: today).toEpochDay().toDouble()

    val longest: Double = max(Span.FIVE_YEARS.days(today, first), last - earliest)

    var days by mutableDoubleStateOf(days.coerceIn(SHORTEST, longest))
        private set

    var end by mutableDoubleStateOf(end ?: last)
        private set

    var granularity by mutableStateOf(granularity(this.days))
        private set

    val start: Double
        get() = end - days

    /** Whether today is in view, or nearly. */
    val current: Boolean
        get() = end >= last - 0.5

    init {
        settle()
    }

    /** Shows the last [days] up to today. */
    fun show(days: Double) {
        this.days = days.coerceIn(SHORTEST, longest)
        end = last
        settle()
    }

    /** Moves by [days], later if positive. */
    fun pan(days: Double) {
        end += days
        settle()
    }

    /** Zooms in by [factor] around [anchor], a fraction of the width from the start. */
    fun zoom(factor: Double, anchor: Double) {
        val at = start + days * anchor
        days = (days / factor).coerceIn(SHORTEST, longest)
        end = at + days * (1 - anchor)
        settle()
    }

    /** Whether [days] is about what is shown. */
    fun shows(days: Double): Boolean = current && abs(this.days - days) < days * 0.03

    private fun settle() {
        end = end.coerceAtMost(last).coerceAtLeast(min(last, earliest + days))
        granularity = granularity(days, granularity)
    }

    companion object {
        fun saver(today: LocalDate, first: LocalDate?) =
            listSaver<Viewport, Double>(
                save = { listOf(it.days, it.end) },
                restore = { Viewport(today, first, it[0], it[1]) },
            )
    }
}

@Composable
fun rememberViewport(today: LocalDate, first: LocalDate?, span: Span): Viewport =
    rememberSaveable(today, first, saver = Viewport.saver(today, first)) {
        Viewport(today, first, span.days(today, first))
    }

/** The periods [granularity] divides the longest stretch [viewport] may show into. */
fun Viewport.range(): Pair<LocalDate, LocalDate> =
    LocalDate.ofEpochDay((last - longest).toLong()) to LocalDate.ofEpochDay(last.toLong() - 1)

/**
 * Pans [viewport] with one finger and zooms it with two, along the time axis only; a long press and
 * drag reads values off where the finger is, which [onScrub] is told as a fraction of the width
 * (ui-design 7).
 */
fun Modifier.timeAxis(viewport: Viewport, onScrub: (Float) -> Unit): Modifier =
    pointerInput(viewport) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val width = size.width.toDouble()
                if (zoom != 1f) viewport.zoom(zoom.toDouble(), centroid.x / width)
                viewport.pan(-pan.x / width * viewport.days)
            }
        }
        .scrub(onScrub)

/**
 * A long press and drag reads values off where the finger is, as a fraction of the width; the last
 * stays after the finger lifts.
 */
fun Modifier.scrub(onScrub: (Float) -> Unit): Modifier =
    pointerInput(onScrub) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onScrub(it.x / size.width) },
            onDrag = { change, _ ->
                change.consume()
                onScrub((change.position.x / size.width).coerceIn(0f, 1f))
            },
        )
    }

/**
 * Tells [onTap] of a short tap that nothing inside took, as a button or a chart's own tap would.
 */
fun Modifier.onBareTap(onTap: () -> Unit): Modifier =
    pointerInput(onTap) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            if (up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                onTap()
            }
        }
    }

/** The first day of the period [granularity] that holds the day [at] days since the epoch. */
fun Granularity.at(at: Double, firstDay: java.time.DayOfWeek): LocalDate =
    start(LocalDate.ofEpochDay(kotlin.math.floor(at).toLong()), firstDay)
