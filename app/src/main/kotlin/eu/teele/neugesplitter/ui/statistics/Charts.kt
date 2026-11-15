package eu.teele.neugesplitter.ui.statistics

import android.icu.text.CompactDecimalFormat
import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.domain.statistics.Link
import eu.teele.neugesplitter.domain.value.Amount
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/** Series colours that colour-blind eyes still tell apart (Okabe and Ito), yellow last. */
private val SERIES =
    listOf(
        Color(0xFFE69F00),
        Color(0xFF56B4E9),
        Color(0xFF009E73),
        Color(0xFF0072B2),
        Color(0xFFD55E00),
        Color(0xFFCC79A7),
        Color(0xFFF0E442),
    )

fun seriesColour(index: Int): Color = SERIES[index.mod(SERIES.size)]

/** In whole units rather than 10⁻⁸ ones, for drawing only. */
fun Amount.toDouble(): Double = units / 1e8

/**
 * One member's balance at the end of each period: the epoch day the period ends before, and the
 * value.
 */
data class Line(val colour: Color, val points: List<Pair<Double, Double>>)

/**
 * One period's bar, from epoch day [start] until [end], in coloured parts stacked away from zero.
 */
data class Bar(val start: Double, val end: Double, val parts: List<Pair<Color, Double>>)

data class Tile(val label: String, val value: Double, val colour: Color)

data class Range(val index: Int, val mean: Double, val min: Double, val max: Double)

/** A node of a flow chart; [label] null for the balance, which goes unlabelled. */
data class FlowNode(val label: String?, val colour: Color, val amount: Double)

@Composable
private fun labelStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun compact(): (Double) -> String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        val format =
            CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
        val formatter: (Double) -> String = { format.format(it) }
        formatter
    }
}

@Composable
private fun dateFormats(): Map<DateLabel, DateTimeFormatter> {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateLabel.entries.associateWith {
            DateTimeFormatter.ofPattern(
                DateFormat.getBestDateTimePattern(locale, it.skeleton),
                locale,
            )
        }
    }
}

/** The ticks for [low] to [high], with the ends animated so that the scale glides as it pans. */
@Composable
private fun animatedTicks(low: Double, high: Double): Triple<Ticks, Float, Float> {
    val ticks = ticks(low, high)
    val bottom by animateFloatAsState(ticks.low.toFloat(), label = "low")
    val top by animateFloatAsState(ticks.high.toFloat(), label = "high")
    return Triple(ticks, bottom, top)
}

/** Grid lines and their values, and the zero line, over a plot [height] high. */
private fun DrawScope.valueAxis(
    ticks: Ticks,
    y: (Double) -> Float,
    height: Float,
    labels: Boolean,
    measurer: TextMeasurer,
    style: TextStyle,
    format: (Double) -> String,
    grid: Color,
    zero: Color,
) {
    for (tick in ticks.values) {
        val at = y(tick)
        if (at < -1f || at > height + 1f) continue
        if (tick != 0.0) drawLine(grid, Offset(0f, at), Offset(size.width, at), 1.dp.toPx())
    }
    drawLine(zero, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
    if (!labels) return
    for (tick in ticks.values) {
        val at = y(tick)
        if (at < -1f || at > height + 1f) continue
        val text = measurer.measure(format(tick), style)
        val top =
            if (at - text.size.height - 1.dp.toPx() < 0) at + 1.dp.toPx()
            else at - text.size.height - 1.dp.toPx()
        drawText(text, topLeft = Offset(2.dp.toPx(), top))
    }
}

/**
 * A plot over the epoch days [start] to [start] + [days] with values from [low] to [high], zero
 * included, and the dates along the bottom if [axes]; [draw] is given where a day and a value go.
 */
@Composable
private fun TimeFrame(
    start: Double,
    days: Double,
    low: Double,
    high: Double,
    firstDay: DayOfWeek,
    axes: Boolean,
    scrub: Double?,
    modifier: Modifier,
    draw: DrawScope.(x: (Double) -> Float, y: (Double) -> Float) -> Unit,
) {
    val (ticks, bottom, top) = animatedTicks(low, high)
    val measurer = rememberTextMeasurer()
    val style = labelStyle()
    val format = compact()
    val dates = dateFormats()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.onSurfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val labelHeight = if (axes) measurer.measure("0", style).size.height + 4.dp.toPx() else 0f
        val height = size.height - labelHeight
        val span = (top - bottom).takeIf { it > 0f } ?: 1f
        val x = { day: Double -> ((day - start) / days * size.width).toFloat() }
        val y = { value: Double -> ((top - value) / span * height).toFloat() }
        clipRect(0f, 0f, size.width, height) { draw(x, y) }
        valueAxis(ticks, y, height, axes, measurer, style, format, grid, zero)
        if (scrub != null) {
            val at = x(scrub)
            drawLine(marker.copy(alpha = 0.6f), Offset(at, 0f), Offset(at, height), 1.dp.toPx())
        }
        if (!axes) return@Canvas
        val (label, ticked) =
            dateTicks(
                LocalDate.ofEpochDay(kotlin.math.ceil(start).toLong()),
                LocalDate.ofEpochDay(kotlin.math.floor(start + days).toLong()),
                firstDay,
            )
        for (date in ticked) {
            val at = x(date.toEpochDay().toDouble())
            drawLine(zero, Offset(at, height), Offset(at, height + 3.dp.toPx()), 1.dp.toPx())
            val text = measurer.measure(dates.getValue(label).format(date), style)
            if (at + text.size.width <= size.width) {
                drawText(text, topLeft = Offset(at + 2.dp.toPx(), height + 3.dp.toPx()))
            }
        }
    }
}

/** Balances over time, one line each, from [start] for [days] (BA-9). */
@Composable
fun BalanceChart(
    lines: List<Line>,
    start: Double,
    days: Double,
    firstDay: DayOfWeek,
    axes: Boolean,
    scrub: Double?,
    modifier: Modifier = Modifier,
) {
    val end = start + days
    var low = 0.0
    var high = 0.0
    for (line in lines) {
        for ((i, point) in line.points.withIndex()) {
            val next = line.points.getOrNull(i + 1)?.first ?: Double.MAX_VALUE
            if (next < start || point.first > end + days / 10) continue
            low = min(low, point.second)
            high = max(high, point.second)
        }
    }
    TimeFrame(start, days, low, high, firstDay, axes, scrub, modifier) { x, y ->
        for (line in lines) {
            val path = Path()
            var started = false
            for ((i, point) in line.points.withIndex()) {
                val next = line.points.getOrNull(i + 1)?.first ?: Double.MAX_VALUE
                val previous = line.points.getOrNull(i - 1)?.first ?: point.first
                if (next < start || previous > end) continue
                if (started) path.lineTo(x(point.first), y(point.second))
                else path.moveTo(x(point.first), y(point.second))
                started = true
            }
            drawPath(path, line.colour, style = Stroke(2.dp.toPx()))
        }
    }
}

/**
 * Spending per period as bars from zero, their parts stacked upward if positive and downward if
 * not, with the running total from the first bar in view as a line if [cumulative] (BA-10).
 */
@Composable
fun SpendingChart(
    bars: List<Bar>,
    cumulative: Boolean,
    start: Double,
    days: Double,
    firstDay: DayOfWeek,
    axes: Boolean,
    scrub: Double?,
    modifier: Modifier = Modifier,
) {
    val end = start + days
    val visible = bars.filter { it.end > start && it.start < end }
    var low = 0.0
    var high = 0.0
    var running = 0.0
    val totals = mutableListOf<Pair<Double, Double>>()
    for (bar in visible) {
        high = max(high, bar.parts.sumOf { max(it.second, 0.0) })
        low = min(low, bar.parts.sumOf { min(it.second, 0.0) })
        running += bar.parts.sumOf { it.second }
        totals += bar.end to running
        if (cumulative) {
            high = max(high, running)
            low = min(low, running)
        }
    }
    val line = MaterialTheme.colorScheme.onSurface
    TimeFrame(start, days, low, high, firstDay, axes, scrub, modifier) { x, y ->
        val pixel = 1.dp.toPx()
        for (bar in visible) {
            val left = x(bar.start)
            val width = x(bar.end) - left
            val gap = if (width > 4 * pixel) max(pixel, width * 0.15f) else 0f
            var up = 0.0
            var down = 0.0
            for ((colour, value) in bar.parts) {
                val from = if (value >= 0) up else down
                val to = from + value
                if (value >= 0) up = to else down = to
                val top = min(y(from), y(to))
                drawRect(
                    colour,
                    Offset(left + gap / 2, top),
                    Size(max(width - gap, pixel), kotlin.math.abs(y(to) - y(from))),
                )
            }
        }
        if (cumulative && totals.isNotEmpty()) {
            val path = Path()
            path.moveTo(x(visible.first().start), y(0.0))
            for ((day, value) in totals) path.lineTo(x(day), y(value))
            drawPath(path, line, style = Stroke(2.dp.toPx()))
        }
    }
}

/** Tiles in proportion to their values, each labelled with its emoji where it fits (BA-11). */
@Composable
fun Treemap(tiles: List<Tile>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium
    Canvas(modifier) {
        val rects = squarify(tiles.map { it.value }, Rect(Offset.Zero, size))
        val inset = 1.dp.toPx()
        for ((tile, rect) in tiles.zip(rects)) {
            if (rect.width <= 2 * inset || rect.height <= 2 * inset) continue
            drawRect(
                tile.colour,
                Offset(rect.left + inset, rect.top + inset),
                Size(rect.width - 2 * inset, rect.height - 2 * inset),
            )
            val text = measurer.measure(tile.label, style)
            if (
                text.size.width + 4 * inset <= rect.width &&
                    text.size.height + 2 * inset <= rect.height
            ) {
                drawText(
                    text,
                    topLeft =
                        Offset(
                            rect.center.x - text.size.width / 2f,
                            rect.center.y - text.size.height / 2f,
                        ),
                )
            }
        }
    }
}

/**
 * The average at each of [slots] positions as a bar from zero, with a line from the lowest to the
 * highest value seen there while [interval] holds; [labels] names a position under the chart, or
 * null to leave it bare (BA-13). A tap or drag picks a position, which [onSelect] is told.
 */
@Composable
fun RangeChart(
    ranges: List<Range>,
    slots: Int,
    firstIndex: Int,
    labels: (Int) -> String?,
    selected: Int?,
    onSelect: ((Int?) -> Unit)?,
    axes: Boolean,
    modifier: Modifier = Modifier,
    interval: Boolean = true,
) {
    val low = ranges.minOfOrNull { if (interval) it.min else it.mean } ?: 0.0
    val high = ranges.maxOfOrNull { if (interval) it.max else it.mean } ?: 0.0
    val (ticks, bottom, top) = animatedTicks(low, high)
    val measurer = rememberTextMeasurer()
    val style = labelStyle()
    val format = compact()
    val colours = MaterialTheme.colorScheme
    val picking =
        if (onSelect == null) Modifier
        else
            Modifier.pointerInput(slots) {
                    detectTapGestures {
                        onSelect(
                            (it.x / size.width * slots).toInt().coerceIn(0, slots - 1) + firstIndex
                        )
                    }
                }
                .scrub { onSelect((it * slots).toInt().coerceIn(0, slots - 1) + firstIndex) }
    Canvas(modifier.then(picking)) {
        val labelHeight = if (axes) measurer.measure("0", style).size.height + 4.dp.toPx() else 0f
        val height = size.height - labelHeight
        val span = (top - bottom).takeIf { it > 0f } ?: 1f
        val y = { value: Double -> ((top - value) / span * height).toFloat() }
        val slot = size.width / slots
        valueAxis(
            ticks,
            y,
            height,
            axes,
            measurer,
            style,
            format,
            colours.outlineVariant,
            colours.onSurfaceVariant,
        )
        for (range in ranges) {
            val centre = (range.index - firstIndex + 0.5f) * slot
            val chosen = selected == null || selected == range.index
            val fill = if (range.mean >= 0) colours.primary else colours.tertiary
            val barTop = min(y(0.0), y(range.mean))
            drawRect(
                fill.copy(alpha = if (chosen) 0.9f else 0.35f),
                Offset(centre - slot * 0.3f, barTop),
                Size(slot * 0.6f, kotlin.math.abs(y(range.mean) - y(0.0))),
            )
            if (!interval) continue
            val whisker = colours.onSurface.copy(alpha = if (chosen) 0.8f else 0.3f)
            val stroke = max(1.dp.toPx(), min(2.dp.toPx(), slot * 0.08f))
            drawLine(whisker, Offset(centre, y(range.min)), Offset(centre, y(range.max)), stroke)
            for (end in listOf(range.min, range.max)) {
                drawLine(
                    whisker,
                    Offset(centre - slot * 0.2f, y(end)),
                    Offset(centre + slot * 0.2f, y(end)),
                    stroke,
                )
            }
        }
        if (!axes) return@Canvas
        for (i in 0 until slots) {
            val label = labels(i + firstIndex) ?: continue
            val text = measurer.measure(label, style)
            val centre = (i + 0.5f) * slot
            drawText(
                text,
                topLeft =
                    Offset(
                        (centre - text.size.width / 2f).coerceIn(0f, size.width - text.size.width),
                        height + 3.dp.toPx(),
                    ),
            )
        }
    }
}

/**
 * Income on the left and expenses on the right, each as stacked nodes, with [links] as bands
 * between them that say their amount where they are thick enough (BA-14).
 */
@Composable
fun FlowChart(
    income: List<FlowNode>,
    expenses: List<FlowNode>,
    links: List<Link>,
    amount: (Amount) -> String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style =
        MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val emoji = MaterialTheme.typography.titleSmall
    Canvas(modifier) {
        val total = income.sumOf { it.amount }
        if (total <= 0) return@Canvas
        val gap = 4.dp.toPx()
        val node = 10.dp.toPx()
        val labels = (income + expenses).mapNotNull { it.label }
        val labelWidth =
            (labels.maxOfOrNull { measurer.measure(it, emoji).size.width } ?: 0) + 4.dp.toPx()
        val scale =
            min(
                    (size.height - gap * (income.size - 1)) / total,
                    (size.height - gap * (expenses.size - 1)) / total,
                )
                .toFloat()
        // The side with fewer gaps is shorter, and centred against the other.
        fun tops(nodes: List<FlowNode>): List<Float> {
            var top = (size.height - total.toFloat() * scale - gap * (nodes.size - 1)) / 2
            return nodes.map {
                val at = top
                top += it.amount.toFloat() * scale + gap
                at
            }
        }
        val leftTops = tops(income)
        val rightTops = tops(expenses)
        val leftX = labelWidth
        val rightX = size.width - labelWidth - node
        val leftUsed = FloatArray(income.size)
        val rightUsed = FloatArray(expenses.size)
        val middle = (leftX + node + rightX) / 2
        for (link in links) {
            val thickness = link.amount.toDouble().toFloat() * scale
            val fromTop = leftTops[link.from] + leftUsed[link.from]
            val toTop = rightTops[link.to] + rightUsed[link.to]
            leftUsed[link.from] += thickness
            rightUsed[link.to] += thickness
            val target = expenses[link.to].takeIf { it.label != null } ?: income[link.from]
            val band =
                Path().apply {
                    moveTo(leftX + node, fromTop)
                    cubicTo(middle, fromTop, middle, toTop, rightX, toTop)
                    lineTo(rightX, toTop + thickness)
                    cubicTo(
                        middle,
                        toTop + thickness,
                        middle,
                        fromTop + thickness,
                        leftX + node,
                        fromTop + thickness,
                    )
                    close()
                }
            drawPath(band, target.colour.copy(alpha = 0.4f))
            val text = measurer.measure(amount(link.amount), style)
            if (
                text.size.height + 2.dp.toPx() <= thickness &&
                    text.size.width <= (rightX - leftX - node) / 2
            ) {
                drawText(
                    text,
                    topLeft =
                        Offset(
                            middle - text.size.width / 2f,
                            (fromTop + toTop + thickness) / 2 - text.size.height / 2f,
                        ),
                )
            }
        }
        for (left in listOf(true, false)) {
            val side = if (left) leftTops else rightTops
            val nodes = if (left) income else expenses
            for ((i, it) in nodes.withIndex()) {
                val height = max(it.amount.toFloat() * scale, 1f)
                val x = if (left) leftX else rightX
                drawRect(it.colour, Offset(x, side[i]), Size(node, height))
                val label = it.label ?: continue
                val text = measurer.measure(label, emoji)
                if (text.size.height * 0.7f > height + gap) continue
                val textX = if (left) x - text.size.width - 2.dp.toPx() else x + node + 2.dp.toPx()
                drawText(
                    text,
                    topLeft = Offset(textX, side[i] + height / 2 - text.size.height / 2f),
                )
            }
        }
    }
}
