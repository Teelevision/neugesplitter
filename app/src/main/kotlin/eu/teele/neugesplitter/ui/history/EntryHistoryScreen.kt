package eu.teele.neugesplitter.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import eu.teele.neugesplitter.domain.history.Revision
import eu.teele.neugesplitter.domain.history.Version
import eu.teele.neugesplitter.ui.DayHeading
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.sideLabels
import java.util.Locale

/**
 * The previous versions of one entry and its deletion, newest first under the day of each (HI-3,
 * ui-design 5).
 */
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
            state.versions.forEachIndexed { index, version ->
                if (index == 0 || day(state.versions[index - 1].at) != day(version.at)) {
                    item(key = "day-$index") { DayHeading(day(version.at), locale) }
                }
                item(key = "version-$index") { VersionItem(version, state, locale) }
            }
        }
    }
}

/**
 * One version, the size of an entry row with what changed cut off where it does not fit; tapping it
 * shows all of it (HI-3).
 */
@Composable
private fun VersionItem(version: Version, state: EntryHistoryState, locale: Locale) {
    var open by rememberSaveable { mutableStateOf(false) }
    val by = state.names[version.by] ?: version.by.hex.take(8)
    val (icon, headline) =
        when (version.change) {
            Revision.CREATED -> R.drawable.ic_add to R.string.created_by
            Revision.EDITED -> R.drawable.ic_edit to R.string.history_edited
            Revision.DELETED -> R.drawable.ic_delete to R.string.history_deleted
            Revision.RESTORED -> R.drawable.ic_history to R.string.history_restored
        }
    val detail =
        when (version.change) {
            Revision.EDITED,
            Revision.RESTORED ->
                LocalResources.current.revised(
                    version.before,
                    version.after,
                    state.scale,
                    state.names,
                    locale,
                )
            else -> summary(version.after, state.scale, locale)
        }
    Column {
        HistoryRow(
            Told(icon, stringResource(headline, by), detail),
            version.at,
            locale,
            Modifier.clickable(
                onClickLabel = stringResource(if (open) R.string.collapse else R.string.expand)
            ) {
                open = !open
            },
        )
        if (open) {
            Column(
                modifier =
                    Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val resources = LocalResources.current
                val lines =
                    version.before
                        ?.let {
                            resources.changes(it, version.after, state.scale, state.names, locale)
                        }
                        .orEmpty()
                if (version.change == Revision.EDITED || version.change == Revision.RESTORED) {
                    for (line in lines) Text(line, style = MaterialTheme.typography.bodyLarge)
                }
                if (lines.isEmpty() || version.change == Revision.CREATED) {
                    Recorded(version.after, state, locale)
                }
            }
        }
    }
}

@Composable
private fun Recorded(entry: Entry, state: EntryHistoryState, locale: Locale) {
    val (payersLabel, beneficiariesLabel) = sideLabels(entry.kind)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Text(entry.description, style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(kindLabel(entry.kind)), color = variant)
    Text(longDate(entry.date, locale), color = variant)
    Text(formatted(entry.amount, state.scale, locale), style = MaterialTheme.typography.bodyLarge)
    SideLines(
        stringResource(payersLabel),
        sideText(entry.payers, entry, state.scale, state.names, locale),
    )
    SideLines(
        stringResource(beneficiariesLabel),
        sideText(entry.beneficiaries, entry, state.scale, state.names, locale),
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
