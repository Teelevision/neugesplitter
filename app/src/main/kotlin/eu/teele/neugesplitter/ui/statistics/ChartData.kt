package eu.teele.neugesplitter.ui.statistics

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.statistics.Granularity
import eu.teele.neugesplitter.domain.statistics.Node
import eu.teele.neugesplitter.domain.statistics.Period
import eu.teele.neugesplitter.domain.statistics.Spending
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.ui.category.categoryName
import eu.teele.neugesplitter.ui.formatted
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.min

/** The charts of a group (BA-8), in the order the statistics screen lists them. */
enum class Chart(@param:StringRes val title: Int) {
    BALANCE(R.string.chart_balance),
    SPENDING(R.string.chart_spending),
    CATEGORIES(R.string.chart_categories),
    MATRIX(R.string.chart_matrix),
    AVERAGES(R.string.chart_averages),
    FLOWS(R.string.chart_flows),
}

/** Stands for no category where a category's emoji would go. */
internal const val NO_CATEGORY = "❔"

/** Stands for the categories too small to show one by one. */
internal const val REST = "⋯"

@Composable internal fun locale(): Locale = LocalConfiguration.current.locales[0]

/** The day weeks begin on where the user lives. */
@Composable
internal fun firstDay(): DayOfWeek {
    val locale = locale()
    return remember(locale) { WeekFields.of(locale).firstDayOfWeek }
}

internal fun Group.emoji(category: CategoryId?): String =
    category?.let { categories[it]?.emoji } ?: NO_CATEGORY

@Composable
internal fun Group.label(category: CategoryId?): String =
    category?.let { categories[it] }?.let { categoryName(it) }
        ?: stringResource(R.string.category_none)

/** A member's colour, the same on every chart. */
internal fun Group.colour(member: MemberId): Color = seriesColour(members.keys.indexOf(member))

/** The members a chart offers, those who have left last. */
internal fun Group.offered(): List<Member> = members.values.sortedBy { it.id in removed }

/** How large [Amount]s are, at this group's scale and without a sign. */
@Composable
internal fun Group.amounts(): (Amount) -> String {
    val locale = locale()
    return { formatted(it.roundedTo(scale), scale, locale) }
}

@Composable
private fun dateFormat(skeleton: String): DateTimeFormatter {
    val locale = locale()
    return remember(locale, skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

@Composable
internal fun windowLabel(window: Window): String {
    val format = dateFormat("dMMMy")
    return stringResource(
        R.string.date_range,
        format.format(window.from),
        format.format(window.until),
    )
}

/** The period of [granularity] that begins on [start], up to [today] at the latest. */
@Composable
internal fun periodLabel(start: LocalDate, granularity: Granularity, today: LocalDate): String =
    when (granularity) {
        Granularity.DAY -> dateFormat("EEEdMMMy").format(start)
        Granularity.WEEK ->
            windowLabel(Window(start, minOf(granularity.next(start).minusDays(1), today)))
        Granularity.MONTH -> dateFormat("MMMMy").format(start)
    }

/** Each of [members]' balance as a line through the ends of [periods], cut off at [last]. */
internal fun balanceLines(
    group: Group,
    periods: List<Period<Map<MemberId, Amount>>>,
    members: List<MemberId>,
    granularity: Granularity,
    last: Double,
): List<Line> =
    members.map { member ->
        Line(
            group.colour(member),
            periods.map {
                min(granularity.next(it.start).toEpochDay().toDouble(), last) to
                    it.value.getValue(member).toDouble()
            },
        )
    }

/**
 * A bar per period, in [colours] for the categories asked for and [grey] for the rest, or if none
 * was asked for in [plain] by its sign.
 */
internal fun spendingBars(
    periods: List<Period<Spending>>,
    granularity: Granularity,
    last: Double,
    colours: List<Color>,
    grey: Color,
    plain: (Double) -> Color,
): List<Bar> =
    periods.map { period ->
        val total = period.value.total.toDouble()
        Bar(
            period.start.toEpochDay().toDouble(),
            min(granularity.next(period.start).toEpochDay().toDouble(), last),
            if (colours.isEmpty()) listOf(plain(total) to total)
            else
                period.value.parts.mapIndexed { i, part -> colours[i] to part.toDouble() } +
                    (grey to period.value.rest.toDouble()),
        )
    }

/** The largest of [ranked] as tiles, no more than [limit] with the rest together in the last. */
internal fun tiles(
    group: Group,
    ranked: List<Pair<CategoryId?, Amount>>,
    limit: Int,
    grey: Color,
): List<Tile> {
    val shown = if (ranked.size > limit) ranked.take(limit - 1) else ranked
    val tiles =
        shown.mapIndexed { i, (category, amount) ->
            Tile(group.emoji(category), amount.toDouble(), seriesColour(i))
        }
    val others = ranked.drop(shown.size).sumOf { it.second.toDouble() }
    return if (others > 0) tiles + Tile(REST, others, grey) else tiles
}

/** One side of a flow chart; its categories are coloured from [offset] on. */
internal fun flowNodes(
    group: Group,
    nodes: List<Node>,
    offset: Int,
    grey: Color,
    balance: Color,
): List<FlowNode> =
    nodes.mapIndexed { i, node ->
        when (node) {
            is Node.Category ->
                FlowNode(group.emoji(node.id), seriesColour(i + offset), node.amount.toDouble())
            is Node.Rest -> FlowNode(REST, grey, node.amount.toDouble())
            is Node.Balance -> FlowNode(null, balance, node.amount.toDouble())
        }
    }
