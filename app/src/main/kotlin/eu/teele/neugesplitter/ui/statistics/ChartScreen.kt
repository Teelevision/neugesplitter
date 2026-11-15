package eu.teele.neugesplitter.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.statistics.Cycle
import eu.teele.neugesplitter.domain.statistics.Measure
import eu.teele.neugesplitter.domain.statistics.Node
import eu.teele.neugesplitter.domain.statistics.Perspective
import eu.teele.neugesplitter.domain.statistics.Statistics
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.ui.category.CategorySheet
import eu.teele.neugesplitter.ui.category.ChosenCategories
import eu.teele.neugesplitter.ui.editor.Segments
import eu.teele.neugesplitter.ui.overview.EntryFilter
import eu.teele.neugesplitter.ui.overview.Listing
import eu.teele.neugesplitter.ui.signed
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** How tall a chart is on its own screen. */
private val CHART = 240.dp

/** The most years the averages go back. */
private const val MOST_YEARS = 30

/**
 * One chart with all its controls (BA-9 to BA-15).
 *
 * @param onEntries lists the entries a category or a total under the chart counts (BA-16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(
    viewModel: StatisticsViewModel,
    chart: Chart,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEntries: (Listing, EntryFilter) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Counts taps that nothing took, each of which lets go of the values read off.
    var taps by remember { mutableIntStateOf(0) }

    // Deleted while open, which only another screen can do.
    LaunchedEffect(state) { if (state.loaded && state.statistics == null) onBack() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(chart.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val statistics = state.statistics ?: return@Scaffold
        val me = state.me?.takeIf { it in statistics.group.members }
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .onBareTap { taps++ }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (chart) {
                Chart.BALANCE -> BalanceDetail(statistics, me, taps)
                Chart.SPENDING -> SpendingDetail(statistics, me, taps, onEntries)
                Chart.CATEGORIES -> CategoriesDetail(statistics, me, onEntries)
                Chart.MATRIX -> MatrixDetail(statistics)
                Chart.AVERAGES -> AveragesDetail(statistics, me, taps)
                Chart.FLOWS -> FlowsDetail(statistics, me, onEntries)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BalanceDetail(statistics: Statistics, me: MemberId?, taps: Int) {
    val group = statistics.group
    val firstDay = firstDay()
    val locale = locale()
    val offered = group.offered()
    var chosen by rememberSaveable {
        mutableStateOf(listOfNotNull((me ?: offered.firstOrNull()?.id)?.hex))
    }
    val members = chosen.map(MemberId::parse).filter { it in group.members }
    val viewport = rememberViewport(statistics.today, statistics.first, Span.MONTH)
    var scrub by remember(taps) { mutableStateOf<Float?>(null) }
    val granularity = viewport.granularity
    val periods =
        remember(statistics, members, granularity, firstDay) {
            val (from, until) = viewport.range()
            statistics.balances(members, from, until, granularity, firstDay)
        }
    val lines =
        remember(periods) { balanceLines(group, periods, members, granularity, viewport.last) }

    TimeControls(statistics, viewport)
    val at = at(viewport, scrub)
    BalanceChart(
        lines,
        viewport.start,
        viewport.days,
        firstDay,
        axes = true,
        scrub = at.takeIf { scrub != null },
        modifier = Modifier.fillMaxWidth().height(CHART).timeAxis(viewport) { scrub = it },
    )
    val period = periods.lastOrNull { it.start.toEpochDay() <= at }
    if (period != null) {
        Text(
            periodLabel(period.start, granularity, statistics.today),
            style = MaterialTheme.typography.titleSmall,
        )
        for (member in members) {
            Readout(
                group.colour(member),
                group.members.getValue(member).name,
                signed(period.value.getValue(member), group.scale, locale),
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (member in offered) {
            val selected = member.id.hex in chosen
            FilterChip(
                selected = selected,
                onClick = {
                    chosen =
                        if (!selected) chosen + member.id.hex
                        else (chosen - member.id.hex).ifEmpty { chosen }
                },
                label = { Text(member.name) },
                leadingIcon = { Dot(group.colour(member.id)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpendingDetail(
    statistics: Statistics,
    me: MemberId?,
    taps: Int,
    onEntries: (Listing, EntryFilter) -> Unit,
) {
    val group = statistics.group
    val firstDay = firstDay()
    val locale = locale()
    val colours = MaterialTheme.colorScheme
    var mine by rememberSaveable { mutableStateOf(false) }
    val perspective = perspective(me, mine)
    var stacking by rememberSaveable { mutableStateOf(Stacking.OFF) }
    // The categories chosen to stack, by their ids; the empty string stands for no category.
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var cumulative by rememberSaveable { mutableStateOf(false) }
    val viewport = rememberViewport(statistics.today, statistics.first, Span.YEAR)
    var scrub by remember(taps) { mutableStateOf<Float?>(null) }
    val from = LocalDate.ofEpochDay(floor(viewport.start).toLong())
    val until = LocalDate.ofEpochDay(ceil(viewport.end).toLong() - 1)
    val inView =
        remember(statistics, perspective, from, until) {
            inView(statistics, perspective, from, until)
        }
    val categories =
        when (stacking) {
            Stacking.OFF -> emptyList()
            Stacking.TOP -> inView.take(TOP).map { it.first }
            Stacking.CHOSEN -> chosen.map { it.ifEmpty { null }?.let(CategoryId::parse) }
        }
    // Every category spent in, which the rest of a stacked bar is narrowed from.
    val present =
        remember(statistics) {
            statistics.entries
                .filter { it.kind != Kind.TRANSFER }
                .mapTo(HashSet()) { it.categoryId }
        }
    val granularity = viewport.granularity
    val periods =
        remember(statistics, perspective, categories, granularity, firstDay) {
            val (from, until) = viewport.range()
            statistics.spending(perspective, from, until, granularity, firstDay, categories)
        }
    val bars =
        remember(periods, colours) {
            spendingBars(
                periods,
                granularity,
                viewport.last,
                categories.indices.map(::seriesColour),
                colours.outline,
            ) {
                if (it >= 0) colours.primary else colours.tertiary
            }
        }

    PerspectiveToggle(me, mine) { mine = it }
    TimeControls(statistics, viewport)
    val at = at(viewport, scrub)
    SpendingChart(
        bars,
        cumulative,
        viewport.start,
        viewport.days,
        firstDay,
        axes = true,
        scrub = at.takeIf { scrub != null },
        modifier = Modifier.fillMaxWidth().height(CHART).timeAxis(viewport) { scrub = it },
    )
    val index = periods.indexOfLast { it.start.toEpochDay() <= at }
    if (index >= 0) {
        val period = periods[index]
        val heading = periodLabel(period.start, granularity, statistics.today)
        val end = minOf(granularity.next(period.start).minusDays(1), statistics.today)
        val during = subtitle(heading, perspective)
        val open = { title: String, categories: Collection<CategoryId?> ->
            onEntries(
                Listing(title, during),
                counted(perspective, period.start, end, categories = categories),
            )
        }
        Text(heading, style = MaterialTheme.typography.titleSmall)
        val net = stringResource(R.string.chart_spending)
        Readout(
            null,
            stringResource(R.string.spending_net_label),
            signed(period.value.total, group.scale, locale),
            onClick = { open(net, emptyList()) },
        )
        if (cumulative) {
            val summed =
                periods.subList(0, index + 1).filter {
                    granularity.next(it.start).toEpochDay() > viewport.start
                }
            val running = Amount.sum(summed.map { it.value.total })
            val since = summed.firstOrNull()?.start ?: period.start
            val title = stringResource(R.string.running_total)
            val throughout = subtitle(windowLabel(Window(since, end)), perspective)
            Readout(
                null,
                title,
                signed(running, group.scale, locale),
                onClick = {
                    onEntries(Listing(title, throughout), counted(perspective, since, end))
                },
            )
        }
        categories.forEachIndexed { i, category ->
            val label = "${group.emoji(category)} ${group.label(category)}"
            Readout(
                seriesColour(i),
                label,
                signed(period.value.parts[i], group.scale, locale),
                onClick = { open(label, listOf(category)) },
            )
        }
        if (categories.isNotEmpty()) {
            val label = stringResource(R.string.chart_rest)
            val rest = present - categories.toSet()
            Readout(
                colours.outline,
                label,
                signed(period.value.rest, group.scale, locale),
                onClick = { open(label, rest) }.takeIf { rest.isNotEmpty() },
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = cumulative,
            onClick = { cumulative = !cumulative },
            label = { Text(stringResource(R.string.running_total)) },
        )
    }
    Text(stringResource(R.string.stack_by_category), style = MaterialTheme.typography.titleSmall)
    Segments(
        Stacking.entries,
        stacking,
        {
            when (it) {
                Stacking.OFF -> R.string.stack_off
                Stacking.TOP -> R.string.stack_top
                Stacking.CHOSEN -> R.string.category_choose
            }
        },
        {
            if (it == Stacking.CHOSEN) {
                if (chosen.isEmpty()) chosen = inView.take(TOP).map { (id) -> id?.hex.orEmpty() }
                choosing = true
            }
            stacking = it
        },
        compact = true,
    )
    val all = group.categories.values.toList()
    if (stacking == Stacking.CHOSEN) {
        ChosenCategories(
            all,
            categories,
            stringResource(R.string.category_choose),
            onRemove = { chosen = chosen - it?.hex.orEmpty() },
            onChoose = { choosing = true },
            marker = ::seriesColour,
        )
    }
    if (choosing) {
        val amounts = group.amounts()
        CategorySheet(
            all,
            inView.map { (id, amount) -> id to amounts(amount) },
            categories,
            onChange = { picked -> chosen = picked.map { it?.hex.orEmpty() } },
            onDismiss = { choosing = false },
            limit = SERIES_COUNT,
            marker = ::seriesColour,
        )
    }
}

/** How stacked net spending is: not, by the largest categories in view, or as chosen. */
private enum class Stacking {
    OFF,
    TOP,
    CHOSEN,
}

/** How many categories [Stacking.TOP] stacks. */
private const val TOP = 5

/** Each category's expenses and income from [from] to [until], the largest first. */
private fun inView(
    statistics: Statistics,
    perspective: Perspective,
    from: LocalDate,
    until: LocalDate,
): List<Pair<CategoryId?, Amount>> {
    val sums = LinkedHashMap<CategoryId?, Amount>()
    for (kind in listOf(Kind.EXPENSE, Kind.INCOME)) {
        for ((id, amount) in statistics.categories(kind, perspective, from, until)) {
            sums[id] = (sums[id] ?: Amount.ZERO) + amount
        }
    }
    return sums.toList().sortedByDescending { it.second.units }
}

@Composable
private fun CategoriesDetail(
    statistics: Statistics,
    me: MemberId?,
    onEntries: (Listing, EntryFilter) -> Unit,
) {
    val group = statistics.group
    var mine by rememberSaveable { mutableStateOf(false) }
    val perspective = perspective(me, mine)
    var span by rememberSaveable { mutableStateOf(Span.YEAR) }
    var back by rememberSaveable { mutableStateOf(0) }
    val window = span.window(statistics.today, statistics.first, back)
    val ranked =
        remember(statistics, perspective, window) {
            statistics.categories(Kind.EXPENSE, perspective, window.from, window.until)
        }
    val during = subtitle(windowLabel(window), perspective)
    val labels = ranked.associate { (id) -> id to "${group.emoji(id)} ${group.label(id)}" }
    val open = { title: String, categories: Collection<CategoryId?> ->
        onEntries(
            Listing(title, during),
            counted(perspective, window.from, window.until, Kind.EXPENSE, categories),
        )
    }

    PerspectiveToggle(me, mine) { mine = it }
    PeriodControls(
        statistics,
        span,
        back,
        {
            span = it
            back = 0
        },
        { back = it },
    )
    val total = stringResource(R.string.total_spending)
    Readout(
        null,
        total,
        group.amounts()(Amount.sum(ranked.map { it.second })),
        onClick = { open(total, emptyList()) }.takeIf { ranked.isNotEmpty() },
    )
    RankedBars(group, ranked) { open(labels.getValue(it), listOf(it)) }
}

@Composable
private fun MatrixDetail(statistics: Statistics) {
    val group = statistics.group
    var measure by rememberSaveable { mutableStateOf(Measure.CONSUMED) }
    var span by rememberSaveable { mutableStateOf(Span.YEAR) }
    var back by rememberSaveable { mutableStateOf(0) }
    val window = span.window(statistics.today, statistics.first, back)
    val cells =
        remember(statistics, measure, window) {
            statistics.matrix(measure, window.from, window.until)
        }

    Segments(
        listOf(Measure.CONSUMED, Measure.PAID),
        measure,
        { if (it == Measure.PAID) R.string.measure_paid else R.string.measure_consumed },
        { measure = it },
    )
    PeriodControls(
        statistics,
        span,
        back,
        {
            span = it
            back = 0
        },
        { back = it },
    )
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        MatrixGrid(group, cells, rows = Int.MAX_VALUE, detailed = true)
    }
}

@Composable
private fun AveragesDetail(statistics: Statistics, me: MemberId?, taps: Int) {
    val group = statistics.group
    val firstDay = firstDay()
    val locale = locale()
    var mine by rememberSaveable { mutableStateOf(false) }
    val perspective = perspective(me, mine)
    var cycle by rememberSaveable { mutableStateOf(Cycle.MONTH) }
    var years by rememberSaveable { mutableStateOf(AVERAGE_YEARS) }
    var interval by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable(cycle, taps) { mutableStateOf<Int?>(null) }
    val positions =
        remember(statistics, cycle, years, perspective, firstDay) {
            statistics.averages(cycle, years, perspective, firstDay)
        }
    val (slots, firstIndex) =
        when (cycle) {
            Cycle.WEEK -> 7 to 0
            Cycle.MONTH -> 31 to 1
            Cycle.YEAR -> 12 to 1
        }

    Segments(
        Cycle.entries,
        cycle,
        {
            when (it) {
                Cycle.WEEK -> R.string.cycle_week
                Cycle.MONTH -> R.string.cycle_month
                Cycle.YEAR -> R.string.cycle_year
            }
        },
        { cycle = it },
    )
    PerspectiveToggle(me, mine) { mine = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { years-- }, enabled = years > 1) {
            Icon(painterResource(R.drawable.ic_minus), stringResource(R.string.fewer_years))
        }
        Text(
            pluralStringResource(R.plurals.years, years, years),
            Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        val further =
            statistics.first?.let { it < statistics.today.minusYears(years.toLong()) } == true
        IconButton(onClick = { years++ }, enabled = further && years < MOST_YEARS) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.more_years))
        }
    }
    RangeChart(
        positions.map { Range(it.index, it.mean.toDouble(), it.min.toDouble(), it.max.toDouble()) },
        slots,
        firstIndex,
        labels = { index ->
            when (cycle) {
                Cycle.WEEK ->
                    firstDay.plus(index.toLong()).getDisplayName(TextStyle.SHORT_STANDALONE, locale)
                Cycle.MONTH -> index.takeIf { it == 1 || it % 5 == 0 }?.toString()
                Cycle.YEAR -> Month.of(index).getDisplayName(TextStyle.NARROW_STANDALONE, locale)
            }
        },
        selected = selected,
        onSelect = { selected = it },
        axes = true,
        modifier = Modifier.fillMaxWidth().height(CHART),
        interval = interval,
    )
    FilterChip(
        selected = interval,
        onClick = { interval = !interval },
        label = { Text(stringResource(R.string.average_interval)) },
    )
    val position = positions.firstOrNull { it.index == selected }
    if (position == null) {
        Text(
            stringResource(R.string.averages_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            when (cycle) {
                Cycle.WEEK ->
                    firstDay
                        .plus(position.index.toLong())
                        .getDisplayName(TextStyle.FULL_STANDALONE, locale)
                Cycle.MONTH -> stringResource(R.string.average_day, position.index)
                Cycle.YEAR ->
                    Month.of(position.index).getDisplayName(TextStyle.FULL_STANDALONE, locale)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(
                R.string.average_readout,
                signed(position.mean, group.scale, locale),
                signed(position.min, group.scale, locale),
                signed(position.max, group.scale, locale),
            )
        )
    }
}

@Composable
private fun FlowsDetail(
    statistics: Statistics,
    me: MemberId?,
    onEntries: (Listing, EntryFilter) -> Unit,
) {
    val group = statistics.group
    val colours = MaterialTheme.colorScheme
    val amounts = group.amounts()
    var mine by rememberSaveable { mutableStateOf(false) }
    val perspective = perspective(me, mine)
    var span by rememberSaveable { mutableStateOf(Span.YEAR) }
    var back by rememberSaveable { mutableStateOf(0) }
    val window = span.window(statistics.today, statistics.first, back)
    val flows =
        remember(statistics, perspective, window) {
            statistics.flows(perspective, window.from, window.until, limit = 6)
        }
    val income =
        flowNodes(group, flows.income, flows.expenses.size, colours.outline, colours.outlineVariant)
    val expenses = flowNodes(group, flows.expenses, 0, colours.outline, colours.outlineVariant)

    PerspectiveToggle(me, mine) { mine = it }
    PeriodControls(
        statistics,
        span,
        back,
        {
            span = it
            back = 0
        },
        { back = it },
    )
    FlowChart(income, expenses, flows.links, amounts, Modifier.fillMaxWidth().height(320.dp))
    for ((kind, nodes, drawn) in
        listOf(
            Triple(Kind.INCOME, flows.income, income),
            Triple(Kind.EXPENSE, flows.expenses, expenses),
        )) {
        if (nodes.isEmpty()) continue
        Text(
            stringResource(
                if (kind == Kind.INCOME) R.string.kind_income else R.string.kind_expense
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        val during = subtitle(windowLabel(window), perspective, kind)
        val open = { title: String, categories: Collection<CategoryId?> ->
            onEntries(
                Listing(title, during),
                counted(perspective, window.from, window.until, kind, categories),
            )
        }
        val shown = nodes.filterIsInstance<Node.Category>().mapTo(HashSet()) { it.id }
        nodes.forEachIndexed { i, node ->
            val label =
                when (node) {
                    is Node.Category -> "${group.emoji(node.id)} ${group.label(node.id)}"
                    is Node.Rest -> "$REST ${stringResource(R.string.chart_rest)}"
                    is Node.Balance ->
                        stringResource(
                            if (kind == Kind.INCOME) R.string.flows_deficit
                            else R.string.flows_surplus
                        )
                }
            Readout(
                drawn[i].colour,
                label,
                amounts(node.amount),
                onClick =
                    when (node) {
                        is Node.Category -> {
                            { open(label, listOf(node.id)) }
                        }
                        is Node.Rest -> {
                            {
                                val all =
                                    statistics
                                        .categories(kind, perspective, window.from, window.until)
                                        .map { it.first }
                                open(label, all - shown)
                            }
                        }
                        is Node.Balance -> null
                    },
            )
        }
    }
}

/** The spans to pick from and a button back to today, for a chart along a time axis. */
@Composable
private fun TimeControls(statistics: Statistics, viewport: Viewport) {
    val today = statistics.today
    val first = statistics.first
    Segments(
        Span.entries,
        Span.entries.firstOrNull { viewport.shows(it.days(today, first)) },
        { checkNotNull(it).label },
        { viewport.show(checkNotNull(it).days(today, first)) },
        compact = true,
    )
    if (!viewport.current) {
        TextButton(onClick = { viewport.show(viewport.days) }) {
            Text(stringResource(R.string.today))
        }
    }
}

/** The spans to pick from, and steps back and forth by one, for a chart of one stretch of time. */
@Composable
private fun PeriodControls(
    statistics: Statistics,
    span: Span,
    back: Int,
    onSpan: (Span) -> Unit,
    onBack: (Int) -> Unit,
) {
    Segments(Span.entries, span, { it.label }, onSpan, compact = true)
    val window = span.window(statistics.today, statistics.first, back)
    val first = statistics.first
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onBack(back + 1) },
            enabled = span != Span.ALL && first != null && window.from > first,
        ) {
            Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.earlier))
        }
        Text(windowLabel(window), Modifier.weight(1f), textAlign = TextAlign.Center)
        IconButton(onClick = { onBack(back - 1) }, enabled = back > 0) {
            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.later))
        }
    }
}

/** The whole group's spending, or [me]'s share of it if [mine]. */
private fun perspective(me: MemberId?, mine: Boolean): Perspective =
    if (me != null && mine) Perspective.Share(me) else Perspective.Everyone

/**
 * The entries [perspective] counts from [from] to [until], of [kind] if one and in [categories] if
 * any; null among them stands for no category (BA-16).
 */
private fun counted(
    perspective: Perspective,
    from: LocalDate,
    until: LocalDate,
    kind: Kind? = null,
    categories: Collection<CategoryId?> = emptyList(),
) =
    EntryFilter(
        kind = kind,
        beneficiaries = setOfNotNull((perspective as? Perspective.Share)?.member),
        categories = categories.filterNotNullTo(HashSet()),
        uncategorized = null in categories,
        from = EntryDate.of(from),
        until = EntryDate.of(until),
        transfers = false,
    )

/**
 * Under the title of a chart's entries: [kind] if one, [range], and whether it is the user's share.
 */
@Composable
private fun subtitle(range: String, perspective: Perspective, kind: Kind? = null): String =
    listOfNotNull(
            kind?.let {
                stringResource(
                    if (it == Kind.INCOME) R.string.kind_income else R.string.kind_expense
                )
            },
            range,
            if (perspective is Perspective.Share) stringResource(R.string.perspective_me) else null,
        )
        .joinToString(" · ")

/** Shown only to someone acting as a member. */
@Composable
private fun PerspectiveToggle(me: MemberId?, mine: Boolean, onChange: (Boolean) -> Unit) {
    if (me == null) return
    Segments(
        listOf(false, true),
        mine,
        { if (it) R.string.perspective_me else R.string.perspective_group },
        onChange,
    )
}

/** The epoch day read off: where the finger last held, else the last one in view. */
private fun at(viewport: Viewport, scrub: Float?): Double =
    if (scrub != null) viewport.start + scrub * viewport.days
    else floor(min(viewport.end, viewport.last) - 0.5)

@Composable
private fun Dot(colour: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
}
