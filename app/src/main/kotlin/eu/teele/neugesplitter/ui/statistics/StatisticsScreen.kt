package eu.teele.neugesplitter.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.statistics.Cycle
import eu.teele.neugesplitter.domain.statistics.Granularity
import eu.teele.neugesplitter.domain.statistics.Measure
import eu.teele.neugesplitter.domain.statistics.Node
import eu.teele.neugesplitter.domain.statistics.Perspective
import eu.teele.neugesplitter.domain.statistics.Statistics
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.ui.position
import eu.teele.neugesplitter.ui.signed

/** The years the averages go back by default. */
internal const val AVERAGE_YEARS = 5

/** Every chart of a group as a card, each opening its own screen (BA-8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    onBack: () -> Unit,
    onOpen: (Chart) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Deleted while open, which only another screen can do.
    LaunchedEffect(state) { if (state.loaded && state.statistics == null) onBack() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.statistics)) },
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
        if (statistics.first == null) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.statistics_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Chart.entries, key = { it.name }) { chart ->
                Preview(chart, statistics, state.me) { onOpen(chart) }
            }
        }
    }
}

@Composable
private fun Preview(chart: Chart, statistics: Statistics, me: MemberId?, onClick: () -> Unit) {
    val group = statistics.group
    val firstDay = firstDay()
    val locale = locale()
    val colours = MaterialTheme.colorScheme
    val year = Span.YEAR.window(statistics.today, statistics.first)
    when (chart) {
        Chart.BALANCE -> {
            val member =
                me?.takeIf { it in group.members } ?: group.offered().firstOrNull()?.id ?: return
            val last = statistics.today.toEpochDay() + 1.0
            val lines =
                remember(statistics, member, firstDay) {
                    val periods =
                        statistics.balances(
                            listOf(member),
                            year.from.minusDays(1),
                            year.until,
                            Granularity.DAY,
                            firstDay,
                        )
                    balanceLines(group, periods, listOf(member), Granularity.DAY, last)
                }
            val balance = statistics.settlement[member] ?: Amount.ZERO
            val headline =
                if (member == me) position(balance, group.scale)
                else
                    AnnotatedString(
                        "${group.members.getValue(member).name} ${signed(balance, group.scale, locale)}"
                    )
            Card(chart, windowLabel(year), headline, onClick) {
                BalanceChart(
                    lines,
                    year.from.toEpochDay().toDouble(),
                    year.days.toDouble(),
                    firstDay,
                    axes = false,
                    scrub = null,
                    modifier = it,
                )
            }
        }
        Chart.SPENDING -> {
            val granularity = granularity(year.days.toDouble())
            val last = statistics.today.toEpochDay() + 1.0
            val periods =
                remember(statistics, firstDay) {
                    statistics.spending(
                        Perspective.Everyone,
                        year.from,
                        year.until,
                        granularity,
                        firstDay,
                    )
                }
            val bars =
                remember(periods, colours) {
                    spendingBars(periods, granularity, last, emptyList(), colours.outline) {
                        if (it >= 0) colours.primary else colours.tertiary
                    }
                }
            val total = Amount.sum(periods.map { it.value.total })
            Card(
                chart,
                windowLabel(year),
                AnnotatedString(
                    stringResource(R.string.spending_net, signed(total, group.scale, locale))
                ),
                onClick,
            ) {
                SpendingChart(
                    bars,
                    cumulative = false,
                    year.from.toEpochDay().toDouble(),
                    year.days.toDouble(),
                    firstDay,
                    axes = false,
                    scrub = null,
                    modifier = it,
                )
            }
        }
        Chart.CATEGORIES -> {
            val ranked =
                remember(statistics) {
                    statistics.categories(Kind.EXPENSE, Perspective.Everyone, year.from, year.until)
                }
            val top = ranked.firstOrNull()
            val amounts = group.amounts()
            Card(
                chart,
                windowLabel(year),
                top?.let { (category, amount) ->
                    AnnotatedString(
                        "${group.emoji(category)} ${group.label(category)} · ${amounts(amount)}"
                    )
                },
                onClick,
            ) {
                Treemap(tiles(group, ranked, 6, colours.outline), it)
            }
        }
        Chart.MATRIX -> {
            val cells =
                remember(statistics) { statistics.matrix(Measure.CONSUMED, year.from, year.until) }
            Card(chart, windowLabel(year), null, onClick) {
                MatrixGrid(
                    group,
                    cells,
                    rows = 4,
                    detailed = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Chart.AVERAGES -> {
            val ranges =
                remember(statistics, firstDay) {
                    statistics
                        .averages(Cycle.MONTH, AVERAGE_YEARS, Perspective.Everyone, firstDay)
                        .map {
                            Range(
                                it.index,
                                it.mean.toDouble(),
                                it.min.toDouble(),
                                it.max.toDouble(),
                            )
                        }
                }
            val from =
                maxOf(
                    checkNotNull(statistics.first),
                    statistics.today.minusYears(AVERAGE_YEARS.toLong()).plusDays(1),
                )
            Card(
                chart,
                windowLabel(Window(from, statistics.today)),
                AnnotatedString(stringResource(R.string.cycle_month)),
                onClick,
            ) {
                RangeChart(
                    ranges,
                    slots = 31,
                    firstIndex = 1,
                    labels = { null },
                    selected = null,
                    onSelect = null,
                    axes = false,
                    modifier = it,
                )
            }
        }
        Chart.FLOWS -> {
            val flows =
                remember(statistics) {
                    statistics.flows(Perspective.Everyone, year.from, year.until, limit = 4)
                }
            val income = Amount.sum(flows.income.filterNot { it is Node.Balance }.map { it.amount })
            val expenses =
                Amount.sum(flows.expenses.filterNot { it is Node.Balance }.map { it.amount })
            val amounts = group.amounts()
            Card(
                chart,
                windowLabel(year),
                AnnotatedString(
                    stringResource(R.string.flows_summary, amounts(income), amounts(expenses))
                ),
                onClick,
                height = 160.dp,
            ) {
                FlowChart(
                    flowNodes(
                        group,
                        flows.income,
                        flows.expenses.size,
                        colours.outline,
                        colours.outlineVariant,
                    ),
                    flowNodes(group, flows.expenses, 0, colours.outline, colours.outlineVariant),
                    flows.links,
                    amounts,
                    it,
                )
            }
        }
    }
}

@Composable
private fun Card(
    chart: Chart,
    subtitle: String,
    headline: AnnotatedString?,
    onClick: () -> Unit,
    height: Dp = 120.dp,
    content: @Composable (Modifier) -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(chart.title), style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (headline != null) {
                Text(
                    headline,
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Box(Modifier.padding(top = 12.dp)) { content(Modifier.fillMaxWidth().height(height)) }
        }
    }
}
