package eu.teele.neugesplitter.ui.overview

import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.storage.ListedEntry
import eu.teele.neugesplitter.storage.Overview
import eu.teele.neugesplitter.storage.settings.SyncState
import eu.teele.neugesplitter.ui.DayHeading
import eu.teele.neugesplitter.ui.DeleteAction
import eu.teele.neugesplitter.ui.EntryRow
import eu.teele.neugesplitter.ui.SWIPE_ACTION
import eu.teele.neugesplitter.ui.SwipeToReveal
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.position
import eu.teele.neugesplitter.ui.sync.GroupTitle
import eu.teele.neugesplitter.ui.sync.SyncHaltedCard
import eu.teele.neugesplitter.ui.sync.ago
import eu.teele.neugesplitter.ui.sync.failed
import eu.teele.neugesplitter.ui.theme.groupColour
import eu.teele.neugesplitter.ui.theme.largeText
import eu.teele.neugesplitter.ui.theme.onGroupColour
import java.time.Instant
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/**
 * A group's total spending, the suggestions waiting, how its sync stands in brief, and its entries,
 * newest first (ui-design 4).
 *
 * @param onActivity opens the group's activity, where its sync stands in full and its suggestions
 *   are decided.
 * @param onBalances opens who owes whom.
 * @param onTodos opens the group's todo list (TD-1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    viewModel: OverviewViewModel,
    deleteRequested: EntryId?,
    onDeleteTaken: () -> Unit,
    onBack: () -> Unit,
    onOpen: (EntryId) -> Unit,
    onEdit: (EntryId) -> Unit,
    onDuplicate: (EntryId) -> Unit,
    onHistory: (EntryId) -> Unit,
    onSyncProblem: () -> Unit,
    onActivity: () -> Unit,
    onBalances: () -> Unit,
    onTodos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val overview = state.overview
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf<ListedEntry?>(null) }
    val deleted = stringResource(R.string.entry_deleted)
    val undo = stringResource(R.string.undo)

    // Deleted while open, which only another screen can do.
    LaunchedEffect(state) { if (state.loaded && overview == null) onBack() }
    LaunchedEffect(deleteRequested) {
        if (deleteRequested != null) {
            viewModel.delete(deleteRequested)
            onDeleteTaken()
        }
    }
    LaunchedEffect(state.deleting) {
        if (state.deleting == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(deleted, undo, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.undoGone()
    }
    // Leaving the overview takes the undo with it, so that the balances elsewhere are current.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.undoGone() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    if (overview != null) {
                        GroupTitle(
                            listOfNotNull(overview.emoji, overview.name).joinToString(" "),
                            state.sync,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (overview != null) {
                        IconButton(onClick = onTodos) {
                            Icon(
                                painterResource(R.drawable.ic_checklist),
                                stringResource(R.string.todos),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (overview != null) {
            Entries(
                overview,
                state,
                onOpen = onOpen,
                onMenu = { menu = it },
                onDelete = viewModel::delete,
                onSyncProblem = onSyncProblem,
                onActivity = onActivity,
                onBalances = onBalances,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    menu?.let { entry ->
        ModalBottomSheet(onDismissRequest = { menu = null }) {
            Text(
                entry.description,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetAction(R.drawable.ic_edit, R.string.edit) {
                menu = null
                onEdit(entry.id)
            }
            SheetAction(R.drawable.ic_content_copy, R.string.duplicate) {
                menu = null
                onDuplicate(entry.id)
            }
            // An occurrence has no versions of its own to list (HI-3).
            if (!entry.repeats) {
                SheetAction(R.drawable.ic_history, R.string.history) {
                    menu = null
                    onHistory(entry.id)
                }
            }
            SheetAction(R.drawable.ic_delete, R.string.delete) {
                menu = null
                viewModel.delete(entry.id)
            }
        }
    }
}

@Composable
private fun SheetAction(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Entries(
    overview: Overview,
    state: OverviewState,
    onOpen: (EntryId) -> Unit,
    onMenu: (ListedEntry) -> Unit,
    onDelete: (EntryId) -> Unit,
    onSyncProblem: () -> Unit,
    onActivity: () -> Unit,
    onBalances: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val days = overview.entries.groupBy { it.date }
    val sync = state.sync
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") { Header(overview, locale, onBalances) }
        if (state.suggestions > 0) {
            item(key = "suggestions") {
                SuggestionsBanner(
                    state.suggestions,
                    onActivity,
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
        if (sync?.halted != null) {
            item(key = "halted") {
                SyncHaltedCard(
                    onSyncProblem,
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        } else if (sync != null) {
            item(key = "sync") { SyncLine(sync, state.now, locale, onActivity) }
        }
        if (days.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.group_no_entries),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                )
            }
        }
        for ((date, entries) in days) {
            item(key = date.toString()) { DayHeading(date, locale) }
            items(entries, key = { it.id.hex }) { entry ->
                // Swiping left only reveals delete, so that a scroll cannot delete (ui-design 4).
                SwipeToReveal(
                    SWIPE_ACTION,
                    actions = { close ->
                        DeleteAction {
                            close()
                            onDelete(entry.id)
                        }
                    },
                ) {
                    EntryRow(
                        entry,
                        overview.scale,
                        locale,
                        Modifier.background(MaterialTheme.colorScheme.surface)
                            .combinedClickable(
                                onClick = { onOpen(entry.id) },
                                onLongClick = { onMenu(entry) },
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Where the user stands and the total, on the group's colour; tapping it opens the balances (BA-7,
 * ui-design 4, 8).
 */
@Composable
private fun Header(overview: Overview, locale: Locale, onBalances: () -> Unit) {
    val content = onGroupColour(overview.colour)
    Column(
        modifier =
            Modifier.padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(groupColour(overview.colour))
                .clickable(onClick = onBalances)
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Nothing to be settled before anything happened, as on the group list.
        overview.own
            ?.takeIf { overview.entries.isNotEmpty() }
            ?.let {
                Text(
                    position(it, overview.scale),
                    style = MaterialTheme.typography.headlineSmall,
                    color = content,
                )
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.total_spending),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
            Text(
                formatted(overview.total, overview.scale, locale),
                style = MaterialTheme.typography.bodyMedium.merge(TABULAR),
                color = content,
            )
        }
    }
}

/**
 * That [count] suggested occurrences wait for someone, leading to Activity where they are decided
 * (RE-4, RE-10).
 */
@Composable
private fun SuggestionsBanner(count: Int, onActivity: () -> Unit, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    val banner =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colours.secondaryContainer)
            .clickable(onClick = onActivity)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
    val message: @Composable RowScope.() -> Unit = {
        Icon(
            painterResource(R.drawable.ic_repeat),
            contentDescription = null,
            tint = colours.onSecondaryContainer,
            modifier = Modifier.size(20.dp),
        )
        Text(
            pluralStringResource(R.plurals.suggestions_waiting, count, count),
            style = MaterialTheme.typography.bodyLarge,
            color = colours.onSecondaryContainer,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
    }
    val review =
        @Composable {
            TextButton(onClick = onActivity) { Text(stringResource(R.string.suggestions_review)) }
        }
    if (largeText()) {
        Column(banner, horizontalAlignment = Alignment.End) {
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = message,
            )
            review()
        }
    } else {
        Row(banner, verticalAlignment = Alignment.CenterVertically) {
            message()
            review()
        }
    }
}

/**
 * How [sync] stands in one line under the total: why the passes fail, or when one last succeeded
 * (SY-6). Tapping it opens the activity, which tells more.
 */
@Composable
private fun SyncLine(sync: SyncState, now: Instant, locale: Locale, onActivity: () -> Unit) {
    Text(
        sync.failure?.let { stringResource(failed(it).first) }
            ?: sync.succeeded?.let { stringResource(R.string.sync_succeeded, ago(it, now, locale)) }
            ?: stringResource(R.string.sync_never),
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (sync.failure != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onActivity)
                .padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
