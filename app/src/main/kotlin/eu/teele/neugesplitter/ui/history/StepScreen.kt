package eu.teele.neugesplitter.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.history.Happened
import eu.teele.neugesplitter.domain.history.Happening
import eu.teele.neugesplitter.domain.history.Revision
import eu.teele.neugesplitter.domain.history.SeriesChange
import eu.teele.neugesplitter.domain.history.Settings
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.sideLabels
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * One step of a group's history in full (HI-10): who did what and when, then each field it changed
 * on its own line, or what it recorded. A step told from several events shows each of them, except
 * settings and categories, whose step already holds all they changed.
 *
 * @param onEntry opens the entry the step names; null where this was opened from it.
 * @param onEntryHistory opens that entry's history; null where this was opened from it.
 * @param onSeries opens the series the step names; null where this was opened from it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StepScreen(
    viewModel: StepViewModel,
    onBack: () -> Unit,
    onEntry: ((EntryId) -> Unit)?,
    onEntryHistory: ((EntryId) -> Unit)?,
    onSeries: ((SeriesId) -> Unit)?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val step = state.step

    LaunchedEffect(state) { if (state.loaded && step == null) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        if (step == null) return@Scaffold
        val told =
            LocalResources.current.told(step, state.names, state.categories, state.scale, locale)
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    // The headline says what kind of change it is.
                    Icon(
                        painterResource(told.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(told.headline, style = MaterialTheme.typography.titleLarge)
                    Text(
                        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
                            .withLocale(locale)
                            .withZone(ZoneId.systemDefault())
                            .format(step.at),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val entry = state.entry
            val series = state.series
            val opens =
                buildList<Pair<Int, () -> Unit>> {
                    if (entry != null && state.entryKept && onEntry != null) {
                        add(R.string.step_open_entry to { onEntry(entry) })
                    }
                    if (entry != null && onEntryHistory != null) {
                        add(R.string.step_entry_history to { onEntryHistory(entry) })
                    }
                    if (series != null && onSeries != null) {
                        add(R.string.series_open to { onSeries(series) })
                    }
                }
            if (opens.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, open) in opens) {
                        OutlinedButton(onClick = open) { Text(stringResource(label)) }
                    }
                }
            }
            HorizontalDivider()
            val happened = step.happened
            if (
                happened is Happened.Changed ||
                    happened is Happened.Category ||
                    state.parts.size <= 1
            ) {
                Part(step, state, locale, headed = false)
            } else {
                for (part in state.parts) Part(part, state, locale, headed = true)
            }
        }
    }
}

/** What [happening] did in full, under its own headline where it is [headed]. */
@Composable
private fun Part(happening: Happening, state: StepState, locale: Locale, headed: Boolean) {
    val resources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (headed) {
            Text(
                resources
                    .told(happening, state.names, state.categories, state.scale, locale)
                    .headline,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        when (val happened = happening.happened) {
            Happened.Created -> state.created?.let { settings -> Created(settings) }
            is Happened.Entry ->
                Revised(
                    happened.before.takeIf {
                        happened.change == Revision.EDITED || happened.change == Revision.RESTORED
                    },
                    happened.after,
                    state,
                    locale,
                )
            is Happened.Recurring ->
                when (val change = happened.change) {
                    is SeriesChange.Started -> {
                        Line(resources.rule(change.segment, locale))
                        Recorded(
                            change.segment.template.on(
                                EntryId.occurrence(happened.series, change.from),
                                change.from,
                            ),
                            state,
                            locale,
                        )
                    }
                    is SeriesChange.Edited -> Revised(change.before, change.after, state, locale)
                    else -> Lines(happening, state, locale)
                }
            else -> Lines(happening, state, locale)
        }
    }
}

@Composable
private fun Lines(happening: Happening, state: StepState, locale: Locale) {
    val told =
        LocalResources.current.told(happening, state.names, state.categories, state.scale, locale)
    for (line in told.lines) {
        Line(line)
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

/** Each field changed from [before] to [after]; [after] as recorded where none or nothing was. */
@Composable
private fun Revised(before: Entry?, after: Entry, state: StepState, locale: Locale) {
    val lines =
        before
            ?.let {
                LocalResources.current.changes(
                    it,
                    after,
                    state.scale,
                    state.names,
                    state.categories,
                    locale,
                )
            }
            .orEmpty()
    if (lines.isEmpty()) Recorded(after, state, locale) else lines.forEach { Line(it) }
}

@Composable
private fun Created(settings: Settings) {
    Field(stringResource(R.string.group_name), settings.name)
    settings.description?.let { Field(stringResource(R.string.group_description), it) }
    settings.emoji?.let { Field(stringResource(R.string.group_emoji), it) }
    Field(stringResource(R.string.field_scale), "${settings.scale.digits}")
}

@Composable
private fun Recorded(entry: Entry, state: StepState, locale: Locale) {
    val (payersLabel, beneficiariesLabel) = sideLabels(entry.kind)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Line(entry.description)
    Text(stringResource(kindLabel(entry.kind)), color = variant)
    Text(longDate(entry.date, locale), color = variant)
    Line(formatted(entry.amount, state.scale, locale))
    Field(
        stringResource(payersLabel),
        sideText(entry.payers, entry, state.scale, state.names, locale),
    )
    Field(
        stringResource(beneficiariesLabel),
        sideText(entry.beneficiaries, entry, state.scale, state.names, locale),
    )
}

@Composable
private fun Field(label: String, text: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Line(text)
}
