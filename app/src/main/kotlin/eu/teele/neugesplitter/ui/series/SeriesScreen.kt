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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.ui.EntryRow
import eu.teele.neugesplitter.ui.editor.DatePick
import eu.teele.neugesplitter.ui.editor.recurrenceText
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindIcon
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

private enum class Dialog {
    STOP,
    DELETE,
}

/**
 * A recurring series: what it produces, how it repeats and whether it runs, with pause, resume,
 * stop from a date and delete (RE-7, RE-9), and the entries it produced.
 *
 * @param onOpen opens one of its entries.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeriesScreen(viewModel: SeriesViewModel, onBack: () -> Unit, onOpen: (EntryId) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
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
                    if (detail.stop == null) {
                        FilledTonalButton(onClick = viewModel::pause) {
                            Text(stringResource(R.string.series_pause))
                        }
                    } else {
                        FilledTonalButton(onClick = viewModel::resume) {
                            Text(stringResource(R.string.series_resume))
                        }
                    }
                    if (!detail.stopped) {
                        OutlinedButton(onClick = { dialog = Dialog.STOP }) {
                            Text(stringResource(R.string.series_stop_from))
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
            if (detail.entries.isNotEmpty()) {
                item(key = "entries") {
                    Column(Modifier.padding(top = 8.dp)) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.series_entries),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                                    .semantics { heading() },
                        )
                    }
                }
                items(detail.entries, key = { it.id.hex }) { entry ->
                    EntryRow(entry, detail.scale, locale, Modifier.clickable { onOpen(entry.id) })
                }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        Dialog.STOP ->
            detail?.let {
                DatePick(
                    EntryDate.of(it.today.value.plusDays(1)),
                    onPicked = { from ->
                        close()
                        viewModel.stop(from)
                    },
                    onDismiss = close,
                )
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
            color = if (detail.stopped) colours.error else colours.onSurfaceVariant,
        )
    }
}

/** Whether it runs, as a sentence with its date (RE-7). */
@Composable
private fun status(detail: SeriesDetail, locale: Locale): String {
    val stop = detail.stop
    return when {
        stop != null && detail.stopped ->
            stringResource(R.string.series_stopped, longDate(stop, locale))
        detail.starts != null ->
            stringResource(R.string.series_starts, longDate(detail.starts, locale))
        stop != null -> stringResource(R.string.series_stops, longDate(stop, locale))
        else -> stringResource(R.string.series_running)
    }
}
