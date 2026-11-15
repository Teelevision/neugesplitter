package eu.teele.neugesplitter.ui.statistics

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Rect
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.statistics.Granularity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The stretches of time a chart shows, back from today (BA-15); [months] is null for all of it. */
enum class Span(val months: Long?, @param:StringRes val label: Int) {
    MONTH(1, R.string.span_month),
    QUARTER(3, R.string.span_quarter),
    YEAR(12, R.string.span_year),
    FIVE_YEARS(60, R.string.span_five_years),
    ALL(null, R.string.span_all);

    /**
     * The window that ends [back] spans before [today], both ends included; all of it starts at
     * [first] and does not step.
     */
    fun window(today: LocalDate, first: LocalDate?, back: Int = 0): Window {
        if (months == null) return Window(minOf(first ?: today, today), today)
        val until = today.minusMonths(months * back)
        return Window(until.minusMonths(months).plusDays(1), until)
    }

    /** How many days the window ending today is long. */
    fun days(today: LocalDate, first: LocalDate?): Double = window(today, first).days.toDouble()
}

/** From [from] to [until], both included. */
data class Window(val from: LocalDate, val until: LocalDate) {
    val days: Long
        get() = ChronoUnit.DAYS.between(from, until) + 1
}

/** The shortest stretch the time axis zooms in to, in days. */
const val SHORTEST = 7.0

/**
 * How finely [days] of time are divided: by day up to about a quarter, by week up to about two
 * years, by month beyond. Near a threshold [current] is kept, so that zooming does not flicker
 * between two (BA-15).
 */
fun granularity(days: Double, current: Granularity? = null): Granularity {
    val plain =
        when {
            days <= 100 -> Granularity.DAY
            days <= 800 -> Granularity.WEEK
            else -> Granularity.MONTH
        }
    val kept =
        when (current) {
            null -> false
            Granularity.DAY -> days <= 115
            Granularity.WEEK -> days in 85.0..920.0
            Granularity.MONTH -> days >= 680
        }
    return if (kept) current!! else plain
}

/** Values an axis from [low] to [high] marks, [step] apart, with zero among them if it is in. */
data class Ticks(val low: Double, val high: Double, val step: Double) {
    val values: List<Double>
        get() =
            generateSequence(low) { it + step }
                .takeWhile { it <= high + step / 2 }
                .map { if (kotlin.math.abs(it) < step / 1e6) 0.0 else it }
                .toList()
}

/** Round ticks around [min] to [max] and zero, about [count] steps apart. */
fun ticks(min: Double, max: Double, count: Int = 4): Ticks {
    val low = min(min, 0.0)
    val high = max(max, 0.0)
    val range = if (high - low > 0) high - low else 1.0
    val raw = range / count
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= raw } * magnitude
    return Ticks(floor(low / step) * step, ceil(high / step) * step, step)
}

/** How a date along the time axis is labelled. */
enum class DateLabel(val skeleton: String) {
    DAY("dMMM"),
    MONTH("MMM"),
    MONTH_YEAR("MMMyy"),
    YEAR("y"),
}

/**
 * The dates from [from] up to [until] that the time axis labels, and how: weeks for a month, months
 * for up to half a year, quarters for up to about two years, and years beyond, no more than about
 * six.
 */
fun dateTicks(
    from: LocalDate,
    until: LocalDate,
    firstDay: DayOfWeek,
): Pair<DateLabel, List<LocalDate>> {
    val days = ChronoUnit.DAYS.between(from, until)
    val (label, start, step) =
        when {
            days <= 45 ->
                Triple(DateLabel.DAY, from.with(TemporalAdjusters.nextOrSame(firstDay)), 7L)
            days <= 200 -> Triple(DateLabel.MONTH, firstOfMonth(from, 1), 0L)
            days <= 800 -> Triple(DateLabel.MONTH_YEAR, firstOfMonth(from, 3), 0L)
            else ->
                Triple(
                    DateLabel.YEAR,
                    from.withDayOfYear(1).let { if (it < from) it.plusYears(1) else it },
                    0L,
                )
        }
    val years = max(1L, ceil(days / 365.25 / 6).toLong())
    val dates =
        generateSequence(start) { date ->
                when (label) {
                    DateLabel.DAY -> date.plusDays(step)
                    DateLabel.MONTH -> date.plusMonths(1)
                    DateLabel.MONTH_YEAR -> date.plusMonths(3)
                    DateLabel.YEAR -> date.plusYears(years)
                }
            }
            .takeWhile { it < until }
            .toList()
    return label to dates
}

/**
 * The first day of the earliest month from [from] on whose number is one more than a multiple of
 * [every].
 */
private fun firstOfMonth(from: LocalDate, every: Int): LocalDate {
    var month = from.withDayOfMonth(1)
    if (month < from) month = month.plusMonths(1)
    while ((month.monthValue - 1) % every != 0) month = month.plusMonths(1)
    return month
}

/**
 * Tiles for [values] filling [bounds], each with an area in proportion to its value and as close to
 * square as the squarified layout gets them (Bruls, Huizing and van Wijk, 2000). The values are
 * positive and largest first.
 */
fun squarify(values: List<Double>, bounds: Rect): List<Rect> {
    val total = values.sum()
    if (values.isEmpty() || total <= 0 || bounds.isEmpty) return values.map { Rect.Zero }
    val scale = bounds.width.toDouble() * bounds.height / total
    val areas = values.map { it * scale }
    val tiles = MutableList(values.size) { Rect.Zero }
    var free = bounds
    var i = 0
    while (i < areas.size) {
        val side = min(free.width, free.height).toDouble()
        var end = i + 1
        var worst = worst(areas, i, end, side)
        while (end < areas.size) {
            val next = worst(areas, i, end + 1, side)
            if (next > worst) break
            worst = next
            end++
        }
        val sum = (i until end).sumOf { areas[it] }
        if (free.width >= free.height) {
            val width = (sum / free.height).toFloat()
            var top = free.top
            for (k in i until end) {
                val height = if (k == end - 1) free.bottom - top else (areas[k] / width).toFloat()
                tiles[k] = Rect(free.left, top, free.left + width, top + height)
                top += height
            }
            free = Rect(free.left + width, free.top, free.right, free.bottom)
        } else {
            val height = (sum / free.width).toFloat()
            var left = free.left
            for (k in i until end) {
                val width = if (k == end - 1) free.right - left else (areas[k] / height).toFloat()
                tiles[k] = Rect(left, free.top, left + width, free.top + height)
                left += width
            }
            free = Rect(free.left, free.top + height, free.right, free.bottom)
        }
        i = end
    }
    return tiles
}

/** The worst aspect ratio of the tiles for areas [from] until [until] laid along [side]. */
private fun worst(areas: List<Double>, from: Int, until: Int, side: Double): Double {
    val sum = (from until until).sumOf { areas[it] }
    var worst = 0.0
    for (k in from until until) {
        val area = areas[k]
        worst = max(worst, max(side * side * area / (sum * sum), sum * sum / (side * side * area)))
    }
    return worst
}
