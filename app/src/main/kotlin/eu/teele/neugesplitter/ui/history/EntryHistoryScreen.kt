package eu.teele.neugesplitter.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.history.Revision
import eu.teele.neugesplitter.domain.history.Version
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.sideLabels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What no value is, on either side of a change. */
private const val NONE = "—"

/** The previous versions of one entry and its deletion, newest first (HI-3, ui-design 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryHistoryScreen(viewModel: EntryHistoryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]

    LaunchedEffect(state) { if (state.loaded && state.versions.isEmpty()) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
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
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    state.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(16.dp),
                )
            }
            itemsIndexed(state.versions) { index, version ->
                if (index > 0) HorizontalDivider()
                VersionItem(version, state, locale)
            }
        }
    }
}

@Composable
private fun VersionItem(version: Version, state: EntryHistoryState, locale: Locale) {
    val by = state.names.getValue(version.by)
    val headline =
        stringResource(
            when (version.change) {
                Revision.CREATED -> R.string.created_by
                Revision.EDITED -> R.string.history_edited
                Revision.DELETED -> R.string.history_deleted
                Revision.RESTORED -> R.string.history_restored
            },
            by,
        )
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text(
            timestamp(version.at, locale),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (version.change) {
            Revision.CREATED -> Recorded(version.after, state, locale)
            Revision.EDITED,
            Revision.RESTORED -> Changes(version.before!!, version.after, state, locale)
            Revision.DELETED -> {}
        }
    }
}

/** The medium date and short time in the device's zone, as [locale] writes them. */
private fun timestamp(at: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(at)

@Composable
private fun Recorded(entry: Entry, state: EntryHistoryState, locale: Locale) {
    val (payersLabel, beneficiariesLabel) = sideLabels(entry.kind)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Text(entry.description, style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(kindLabel(entry.kind)), color = variant)
    Text(longDate(entry.date, locale), color = variant)
    Text(formatted(entry.amount, state.scale, locale), style = MaterialTheme.typography.bodyLarge)
    SideLines(stringResource(payersLabel), sideText(entry.payers, entry, state, locale))
    SideLines(
        stringResource(beneficiariesLabel),
        sideText(entry.beneficiaries, entry, state, locale),
    )
}

@Composable
private fun SideLines(label: String, text: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

/** One line for each field that differs, as old → new (HI-3); a restoration may differ in none. */
@Composable
private fun Changes(before: Entry, after: Entry, state: EntryHistoryState, locale: Locale) {
    val resources = LocalResources.current
    val diff = EntryChanges.between(before, after)
    val (payersLabel, beneficiariesLabel) = sideLabels(after.kind)
    val lines = mutableListOf<String>()
    fun field(label: Int, change: Change<*>, old: String, new: String) {
        if (change is Change.To) {
            lines +=
                resources.getString(R.string.history_change, resources.getString(label), old, new)
        }
    }
    field(
        R.string.field_kind,
        diff.kind,
        resources.getString(kindLabel(before.kind)),
        resources.getString(kindLabel(after.kind)),
    )
    field(R.string.entry_description, diff.description, before.description, after.description)
    field(
        R.string.entry_date,
        diff.date,
        longDate(before.date, locale),
        longDate(after.date, locale),
    )
    field(
        R.string.entry_amount,
        diff.amount,
        formatted(before.amount, state.scale, locale),
        formatted(after.amount, state.scale, locale),
    )
    field(
        payersLabel,
        diff.payers,
        sideText(before.payers, before, state, locale),
        sideText(after.payers, after, state, locale),
    )
    field(
        beneficiariesLabel,
        diff.beneficiaries,
        sideText(before.beneficiaries, before, state, locale),
        sideText(after.beneficiaries, after, state, locale),
    )
    field(R.string.field_note, diff.note, before.note ?: NONE, after.note ?: NONE)
    if (diff.attachments is Change.To) lines += resources.getString(R.string.history_attachments)
    for (line in lines) Text(line, style = MaterialTheme.typography.bodyLarge)
}

/** "Anna 15.00, Ben 15.00": each member and their part of [entry]'s amount (SP-7). */
private fun sideText(side: Side, entry: Entry, state: EntryHistoryState, locale: Locale): String =
    side.parts(entry.amount).entries.joinToString(", ") { (member, part) ->
        "${state.names.getValue(member)} ${formatted(part.roundedTo(state.scale), state.scale, locale)}"
    }
