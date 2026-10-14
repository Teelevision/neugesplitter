package eu.teele.neugesplitter.ui.series

import android.icu.text.ListFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.recurrence.Impact
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Stop
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.ui.DayHeading
import eu.teele.neugesplitter.ui.EntryRow
import eu.teele.neugesplitter.ui.editor.DatePick
import eu.teele.neugesplitter.ui.editor.recurrenceText
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.history.HistoryRow
import eu.teele.neugesplitter.ui.history.day
import eu.teele.neugesplitter.ui.history.told
import eu.teele.neugesplitter.ui.kindIcon
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

private enum class Dialog {
    STOP,
    PAUSE,
    CONTINUE,
    DELETE,
}

/**
 * A recurring series: what it produces, how it repeats and whether it runs, with change, stop,
 * pause, continue and delete (RE-6, RE-7, RE-9), and the entries it produced.
 *
 * @param onChange opens the series to change it from a date on.
 * @param onOpen opens one of its entries.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeriesScreen(
    viewModel: SeriesViewModel,
    onBack: () -> Unit,
    onChange: () -> Unit,
    onOpen: (EntryId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    // The first day of the stop being removed, which tells it apart from the others.
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]

    LaunchedEffect(state) { if (state.loaded && detail == null) onBack() }

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
        if (detail == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "header") { Header(detail, locale) }
            item(key = "actions") {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (detail.stop != null) {
                        FilledTonalButton(onClick = { dialog = Dialog.CONTINUE }) {
                            Text(stringResource(R.string.series_continue))
                        }
                    }
                    if (!detail.ended) {
                        OutlinedButton(onClick = onChange) {
                            Text(stringResource(R.string.series_change))
                        }
                        OutlinedButton(onClick = { dialog = Dialog.PAUSE }) {
                            Text(stringResource(R.string.series_pause))
                        }
                        OutlinedButton(onClick = { dialog = Dialog.STOP }) {
                            Text(stringResource(R.string.series_stop))
                        }
                    }
                    OutlinedButton(onClick = { dialog = Dialog.DELETE }) {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (detail.series.stops.isNotEmpty()) {
                item(key = "stops") { Heading(stringResource(R.string.series_stops_list)) }
                items(detail.series.stops, key = { "stop-${it.from}" }) { stop ->
                    StopRow(stop, locale, onRemove = { removing = stop.from.toString() })
                }
            }
            // Every due date so far, with what became of it, so that a missing entry is explained,
            // and what was done to the series on the day it was done (RE-9, HI-2).
            val waiting = detail.waiting.toSet()
            val leftOut = detail.leftOut.toSet()
            val done = detail.history.groupBy { day(it.at) }
            val days = (detail.entries.map { it.date } + waiting + leftOut + done.keys).distinct()
            if (days.isNotEmpty()) {
                item(key = "entries") { Heading(stringResource(R.string.series_entries)) }
                for (date in days.sortedDescending()) {
                    item(key = "day-$date") { DayHeading(date, locale) }
                    items(detail.entries.filter { it.date == date }, key = { it.id.hex }) { entry ->
                        EntryRow(
                            entry,
                            detail.scale,
                            locale,
                            Modifier.clickable { onOpen(entry.id) },
                        )
                    }
                    if (date in waiting) {
                        item(key = "waiting-$date") {
                            Note(stringResource(R.string.series_waiting))
                        }
                    }
                    if (date in leftOut) {
                        item(key = "left-$date") { Note(stringResource(R.string.series_left_out)) }
                    }
                    done[date]?.forEachIndexed { index, happening ->
                        item(key = "done-$date-$index") {
                            HistoryRow(
                                told(happening, detail.names, detail.scale, locale),
                                happening.at,
                                locale,
                            )
                        }
                    }
                }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        Dialog.STOP ->
            detail?.let {
                StopDialog(it, locale, onDismiss = close) { from ->
                    close()
                    viewModel.stop(from)
                }
            }
        Dialog.PAUSE ->
            detail?.let {
                PauseDialog(it, locale, onDismiss = close) { from, until ->
                    close()
                    viewModel.pause(from, until)
                }
            }
        Dialog.CONTINUE ->
            detail?.stop?.let { stop ->
                ContinueDialog(detail, stop, locale, onDismiss = close) { from ->
                    close()
                    viewModel.resume(from)
                }
            }
        Dialog.DELETE ->
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.series_delete_title)) },
                text = { Text(stringResource(R.string.series_delete_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            close()
                            viewModel.delete()
                        }
                    ) {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
                },
            )
        null -> Unit
    }
    detail
        ?.series
        ?.stops
        ?.find { it.from.toString() == removing }
        ?.let { stop ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = {
                    Text(
                        stringResource(
                            if (stop.until == null) R.string.series_remove_stop
                            else R.string.series_remove_pause
                        )
                    )
                },
                text = { Text(note(detail.change(detail.series.continued(stop)))) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            removing = null
                            viewModel.remove(stop)
                        }
                    ) {
                        Text(stringResource(R.string.series_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { removing = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
}

@Composable
private fun Heading(text: String) {
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider()
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

/** What became of a due date that has no entry. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** One pause or stop, past, in force or ahead, which can be taken back as a whole (RE-7). */
@Composable
private fun StopRow(stop: Stop, locale: Locale, onRemove: () -> Unit) {
    val from = longDate(stop.from, locale)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stop.until?.let {
                stringResource(R.string.series_stop_span, from, longDate(it, locale))
            } ?: stringResource(R.string.series_stop_open, from),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text(stringResource(R.string.series_remove)) }
    }
}

@Composable
private fun Header(detail: SeriesDetail, locale: Locale) {
    val template = detail.segment.template
    val amount = formatted(template.amount, detail.scale, locale)
    val colours = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(48.dp).clip(CircleShape).background(colours.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(kindIcon(template.kind)),
                    stringResource(kindLabel(template.kind)),
                    tint = colours.onSurfaceVariant,
                )
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(template.description, style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(
                        when (template.kind) {
                            Kind.EXPENSE -> R.string.entry_paid_by
                            Kind.INCOME -> R.string.entry_received_by
                            Kind.TRANSFER -> R.string.entry_transfer_from
                        },
                        ListFormatter.getInstance(locale).format(detail.payers),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colours.onSurfaceVariant,
                )
            }
        }
        Text(
            if (template.kind == Kind.INCOME) "+$amount" else amount,
            style = MaterialTheme.typography.displaySmall.merge(TABULAR),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_repeat),
                contentDescription = null,
                tint = colours.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                listOf(
                        recurrenceText(detail.segment.interval, locale),
                        stringResource(
                            if (detail.segment.mode == Mode.SUGGESTED) R.string.mode_suggested
                            else R.string.mode_automatic
                        ),
                    )
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            status(detail, locale),
            style = MaterialTheme.typography.bodyMedium,
            color = if (detail.ended) colours.error else colours.onSurfaceVariant,
        )
        detail.next?.let { next ->
            Text(
                stringResource(R.string.series_next, longDate(next, locale)),
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
            )
        }
    }
}

/** Whether it runs, as a sentence with its dates (RE-7). */
@Composable
private fun status(detail: SeriesDetail, locale: Locale): String {
    val stop = detail.stop
    val starts = detail.starts
    return when {
        stop != null && detail.stopped -> stopText(stop, inForce = true, locale)
        starts != null -> stringResource(R.string.series_starts, longDate(starts, locale))
        stop != null -> stopText(stop, inForce = false, locale)
        else -> stringResource(R.string.series_running)
    }
}

/** How [stop] holds a series, [inForce] today or ahead, as a sentence with its dates (RE-7). */
@Composable
internal fun stopText(stop: Stop, inForce: Boolean, locale: Locale): String {
    val from = longDate(stop.from, locale)
    val until = stop.until?.let { longDate(it, locale) }
    return when {
        until == null && inForce -> stringResource(R.string.series_stopped, from)
        until == null -> stringResource(R.string.series_stops, from)
        inForce -> stringResource(R.string.series_paused, until)
        else -> stringResource(R.string.series_pauses, from, until)
    }
}

/** Stops it for good from a date, at the earliest after its first occurrence (RE-7). */
@Composable
private fun StopDialog(
    detail: SeriesDetail,
    locale: Locale,
    onDismiss: () -> Unit,
    onStop: (EntryDate) -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(detail.stopFrom.toString()) }
    val date = EntryDate.parse(from)
    DatesDialog(
        title = R.string.series_stop_title,
        fields =
            listOf(
                DateField(R.string.series_from, date, { from = it.toString() }) {
                    it >= detail.earliestStop
                }
            ),
        note = note(detail.change(detail.series.stopped(Stop(date)))),
        confirm = R.string.series_stop,
        locale = locale,
        onConfirm = { onStop(date) },
        onDismiss = onDismiss,
    )
}

/** Stops it from a date until it continues by itself, by default skipping one occurrence (RE-7). */
@Composable
private fun PauseDialog(
    detail: SeriesDetail,
    locale: Locale,
    onDismiss: () -> Unit,
    onPause: (EntryDate, EntryDate) -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(detail.pauseFrom.toString()) }
    var until by rememberSaveable { mutableStateOf(detail.pauseUntil(detail.pauseFrom).toString()) }
    val start = EntryDate.parse(from)
    val end = EntryDate.parse(until)
    DatesDialog(
        title = R.string.series_pause_title,
        fields =
            listOf(
                DateField(
                    R.string.series_from,
                    start,
                    {
                        from = it.toString()
                        until = detail.pauseUntil(it).toString()
                    },
                ) {
                    it >= detail.series.start
                },
                DateField(R.string.series_continues_on, end, { until = it.toString() }) {
                    it > start
                },
            ),
        note = note(detail.change(detail.series.stopped(Stop(start, end)))),
        confirm = R.string.series_pause,
        locale = locale,
        onConfirm = { onPause(start, end) },
        onDismiss = onDismiss,
    )
}

/** Has it go on from a date within [stop], by default today or the stop's first day (RE-7). */
@Composable
private fun ContinueDialog(
    detail: SeriesDetail,
    stop: Stop,
    locale: Locale,
    onDismiss: () -> Unit,
    onContinue: (EntryDate) -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(maxOf(detail.today, stop.from).toString()) }
    val date = EntryDate.parse(from)
    val until = stop.until
    DatesDialog(
        title = R.string.series_continue_title,
        fields =
            listOf(
                DateField(R.string.series_from, date, { from = it.toString() }) {
                    it >= stop.from && (until == null || it < until)
                }
            ),
        note = note(detail.change(detail.series.continued(Stop(date, until)))),
        confirm = R.string.series_continue,
        locale = locale,
        onConfirm = { onContinue(date) },
        onDismiss = onDismiss,
    )
}

/** What a change does to the entries already in the balances. */
@Composable
private fun note(impact: Impact): String =
    impactText(impact) ?: stringResource(R.string.series_keeps)

/** What a change to a series does to the entries already in the balances; null if nothing. */
@Composable
internal fun impactText(impact: Impact): String? {
    val (removed, added, changed) = impact
    return listOfNotNull(
            pluralStringResource(R.plurals.series_removes, removed, removed).takeIf { removed > 0 },
            pluralStringResource(R.plurals.series_adds, added, added).takeIf { added > 0 },
            pluralStringResource(R.plurals.series_changes, changed, changed).takeIf { changed > 0 },
        )
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")
}

/** One date in a [DatesDialog]: what it is, and which dates it takes. */
private class DateField(
    val label: Int,
    val date: EntryDate,
    val onChange: (EntryDate) -> Unit,
    val selectable: (EntryDate) -> Boolean,
)

/** A dialog of dates, each picked on a tap, with a note on what confirming does. */
@Composable
private fun DatesDialog(
    title: Int,
    fields: List<DateField>,
    note: String,
    confirm: Int,
    locale: Locale,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                fields.forEachIndexed { index, field ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(onClickLabel = stringResource(field.label)) {
                                picking = index
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            stringResource(field.label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            longDate(field.date, locale),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Text(note, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    picking
        ?.let { fields.getOrNull(it) }
        ?.let { field ->
            DatePick(
                field.date,
                onPicked = {
                    picking = null
                    if (field.selectable(it)) field.onChange(it)
                },
                onDismiss = { picking = null },
                selectable = field.selectable,
            )
        }
}
