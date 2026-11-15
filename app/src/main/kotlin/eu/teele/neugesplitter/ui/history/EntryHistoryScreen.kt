package eu.teele.neugesplitter.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.history.Revision
import eu.teele.neugesplitter.domain.history.Version
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.ui.DayHeading
import java.util.Locale

/**
 * The previous versions of one entry and its deletion, newest first under the day of each (HI-3,
 * ui-design 5).
 *
 * @param onStep opens the step of the group's history that made a version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryHistoryScreen(
    viewModel: EntryHistoryViewModel,
    onBack: () -> Unit,
    onStep: (EventId) -> Unit,
) {
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
                item(key = "version-$index") {
                    VersionItem(version, state, locale) { onStep(version.event) }
                }
            }
        }
    }
}

/**
 * One version, the size of an entry row with what changed cut off where it does not fit; tapping it
 * opens all of it (HI-3).
 */
@Composable
private fun VersionItem(
    version: Version,
    state: EntryHistoryState,
    locale: Locale,
    onOpen: () -> Unit,
) {
    val by = state.names[version.by] ?: version.by.hex.take(8)
    val (icon, headline) =
        when (version.change) {
            Revision.CREATED -> R.drawable.ic_add to R.string.created_by
            Revision.EDITED -> R.drawable.ic_edit to R.string.history_edited
            Revision.DELETED -> R.drawable.ic_delete to R.string.history_deleted
            Revision.RESTORED -> R.drawable.ic_history to R.string.history_restored
        }
    val resources = LocalResources.current
    val lines =
        when (version.change) {
            Revision.EDITED,
            Revision.RESTORED ->
                resources.revisions(
                    version.before,
                    version.after,
                    state.scale,
                    state.names,
                    state.categories,
                    locale,
                )
            else -> listOf(summary(version.after, state.scale, locale))
        }
    val brief =
        version.before
            ?.takeIf { version.change == Revision.EDITED }
            ?.let { resources.changedFields(it, version.after) }
    HistoryRow(
        Told(icon, stringResource(headline, by), lines, brief),
        version.at,
        locale,
        Modifier.clickable(onClick = onOpen),
    )
}
