package eu.teele.neugesplitter.ui.activity

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.TodoId
import eu.teele.neugesplitter.storage.settings.SyncState
import eu.teele.neugesplitter.ui.DayHeading
import eu.teele.neugesplitter.ui.EntryRow
import eu.teele.neugesplitter.ui.GroupMenu
import eu.teele.neugesplitter.ui.SwipeToDismissSnackbarHost
import eu.teele.neugesplitter.ui.history.HistoryRow
import eu.teele.neugesplitter.ui.history.told
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.sync.GroupTitle
import eu.teele.neugesplitter.ui.sync.SyncPullToRefresh
import eu.teele.neugesplitter.ui.sync.ago
import eu.teele.neugesplitter.ui.sync.failed
import eu.teele.neugesplitter.ui.sync.troubled
import eu.teele.neugesplitter.ui.todo.SwipeableTodoRow
import eu.teele.neugesplitter.ui.todo.TodoViewModel
import eu.teele.neugesplitter.ui.todo.TodoWrites
import java.time.Instant
import java.util.Locale

/** How many more steps of the history are laid out at a time. */
private const val PAGE = 200

/**
 * How a group's sync stands, with a manual sync (SY-6, SY-9, TR-12), then the suggestions waiting
 * for someone (RE-4, RE-10) and the todos due (TD-7), above what changed in it, by whom and when
 * (HI-2), with what has not been seen yet highlighted (HI-5, HI-7). The suggestions, the todos and
 * the history fold away (ui-design 4). The todos are swiped for done and delete as in their list,
 * through its [todoViewModel] (TD-5, TD-6).
 *
 * @param onEdit opens a suggestion in the editor, which confirms it as saved (RE-4).
 * @param onStep opens a step of the history in full, by an event it was told from.
 * @param onShare starts sharing a group kept only on this device.
 * @param onSyncProblem opens why the group stopped syncing.
 * @param onTodos opens the todo list.
 * @param onSettings opens the group's settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    todoViewModel: TodoViewModel,
    onBack: () -> Unit,
    onEdit: (EntryId) -> Unit,
    onStep: (EventId) -> Unit,
    onShare: () -> Unit,
    onSyncProblem: () -> Unit,
    onTodos: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val todoState by todoViewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val snackbar = remember { SnackbarHostState() }
    var suggestions by rememberSaveable { mutableStateOf(true) }
    var todos by rememberSaveable { mutableStateOf(true) }
    var history by rememberSaveable { mutableStateOf(true) }
    // Only so many steps are laid out at first, and more as the list nears their end.
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow {
                val info = list.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
            }
            .collect { (last, total) ->
                if (last >= total - PAGE / 4 && shown < viewModel.state.value.history.size)
                    shown += PAGE
            }
    }
    // Those ticked here stay, struck through, so that they can be ticked open again.
    var ticked by remember { mutableStateOf(emptySet<TodoId>()) }
    val due =
        todoState.today?.let { today ->
            todoState.all
                .filter { todo ->
                    todo.due.let { it != null && it <= today } && (!todo.done || todo.id in ticked)
                }
                .sortedBy { it.due }
        } ?: emptyList()

    // Deleted while open, which only another screen can do.
    LaunchedEffect(state) { if (state.loaded && state.title == null) onBack() }
    TodoWrites(todoViewModel, todoState, snackbar)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SwipeToDismissSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { GroupTitle(state.title.orEmpty(), state.sync) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (state.title == null) return@TopAppBar
                    GroupMenu(onSettings) { close ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mark_all_read)) },
                            enabled = state.unseen,
                            onClick = {
                                close()
                                viewModel.readAll()
                            },
                        )
                        val sync = state.sync ?: return@GroupMenu
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (sync.paused) R.string.sync_resume
                                        else R.string.sync_pause
                                    )
                                )
                            },
                            onClick = {
                                close()
                                if (sync.paused) viewModel.resume() else viewModel.pause()
                            },
                        )
                        if (sync.paused && sync.waiting > 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.discard_local)) },
                                onClick = {
                                    close()
                                    discarding = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.title == null) return@Scaffold
        if (discarding) {
            AlertDialog(
                onDismissRequest = { discarding = false },
                title = { Text(stringResource(R.string.discard_local_title)) },
                text = { Text(stringResource(R.string.discard_local_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            discarding = false
                            viewModel.discard()
                        }
                    ) {
                        Text(stringResource(R.string.discard_local_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { discarding = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        SyncPullToRefresh(
            shared = state.sync != null,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item(key = "sync") {
                    SyncCard(state.sync, state.now, locale, viewModel::sync, onShare, onSyncProblem)
                }
                if (state.suggestions.isNotEmpty()) {
                    section("suggestions", R.string.suggestions, suggestions) {
                        suggestions = !suggestions
                    }
                }
                if (state.suggestions.isNotEmpty() && suggestions) {
                    item(key = "suggestions-note") {
                        Text(
                            stringResource(R.string.suggestions_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(state.suggestions, key = { "suggestion-${it.entry.id.hex}" }) { suggestion
                        ->
                        SuggestionRow(
                            suggestion,
                            state,
                            locale,
                            onConfirm = { viewModel.confirm(suggestion) },
                            onEdit = { onEdit(suggestion.entry.id) },
                            onDecline = { viewModel.decline(suggestion) },
                        )
                    }
                }
                if (due.isNotEmpty()) {
                    section("todos", R.string.todos_due, todos) { todos = !todos }
                }
                if (due.isNotEmpty() && todos) {
                    items(due, key = { "todo-${it.id.hex}" }) { todo ->
                        SwipeableTodoRow(
                            todo,
                            todoState.names,
                            locale,
                            onDone = {
                                ticked += todo.id
                                todoViewModel.done(todo.id, it)
                            },
                            onDelete = { todoViewModel.delete(todo.id) },
                            onClick = onTodos,
                        )
                    }
                }
                section("history", R.string.history, history) { history = !history }
                if (history) {
                    val steps = state.history
                    for (index in 0 until minOf(shown, steps.size)) {
                        val item = steps[index]
                        val key = item.step.happening.event.hex
                        if (index == 0 || steps[index - 1].day != item.day) {
                            item(key = "day-$key") { DayHeading(item.day, locale) }
                        }
                        item(key = "happened-$key") {
                            HappeningRow(item, state, locale) {
                                if (item.unseen) viewModel.read(item.step)
                                onStep(item.step.happening.event)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(
    key: String,
    @StringRes title: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    item(key = key) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        painterResource(
                            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                        ),
                        stringResource(if (expanded) R.string.collapse else R.string.expand),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

/** A suggested occurrence, with what can be done about it (RE-4). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionRow(
    suggestion: Suggestion,
    state: ActivityState,
    locale: Locale,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onDecline: () -> Unit,
) {
    Column {
        EntryRow(suggestion.entry, state.scale, locale)
        Text(
            stringResource(R.string.suggestion_due, longDate(suggestion.due, locale)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 72.dp, end = 16.dp),
        )
        // At a large text size the three do not fit on one line, and confirming wraps to the next.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.suggestion_decline)) }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
            // Takes the rest of its line, on this one or the next, and sits at its end.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                FilledTonalButton(onClick = onConfirm) {
                    Text(stringResource(R.string.suggestion_confirm))
                }
            }
        }
    }
}

/** One change, the size of an entry row; tapping it reads it and opens it in full (HI-2, HI-5). */
@Composable
private fun HappeningRow(
    item: HistoryItem,
    state: ActivityState,
    locale: Locale,
    onOpen: () -> Unit,
) {
    val resources = LocalResources.current
    val told =
        remember(item.step.happening, state.names, state.categories, state.scale, locale) {
            resources.told(item.step.happening, state.names, state.categories, state.scale, locale)
        }
    HistoryRow(
        told,
        item.step.happening.at,
        locale,
        unseen = item.unseen,
        unsynced = item.unsynced,
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

/**
 * How the group's sync stands (SY-6), or that it is kept only here, which a null [sync] is, on a
 * card coloured by which: primary while it syncs, error once it fails or halts (SY-15), neutral
 * when kept here. A halted group's card opens why.
 */
@Composable
private fun SyncCard(
    sync: SyncState?,
    now: Instant,
    locale: Locale,
    onSync: () -> Unit,
    onShare: () -> Unit,
    onSyncProblem: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val (container, content) =
        when {
            sync == null -> colours.surfaceContainerHigh to colours.onSurface
            sync.troubled -> colours.errorContainer to colours.onErrorContainer
            else -> colours.primaryContainer to colours.onPrimaryContainer
        }
    CompositionLocalProvider(LocalContentColor provides content) {
        Column(
            modifier =
                Modifier.padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(container)
                    .then(
                        if (sync?.halted != null) Modifier.clickable(onClick = onSyncProblem)
                        else Modifier
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sync == null) {
                Text(
                    stringResource(R.string.storage_local),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.sync_local_text))
                OutlinedButton(onClick = onShare) { Text(stringResource(R.string.storage_set_up)) }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The top bar already says how it stands to a screen reader.
                Icon(
                    painterResource(
                        when {
                            sync.troubled -> R.drawable.ic_warning
                            sync.paused -> R.drawable.ic_cloud_off
                            else -> R.drawable.ic_check
                        }
                    ),
                    null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (sync.paused) stringResource(R.string.sync_paused)
                    else
                        sync.succeeded?.let {
                            stringResource(R.string.sync_succeeded, ago(it, now, locale))
                        } ?: stringResource(R.string.sync_never),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            val explained =
                sync.halted?.let { R.string.sync_halted to R.string.sync_halted_text }
                    ?: sync.failure?.let(::failed)
            explained?.let { (title, text) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(text))
                }
            }
            Text(
                if (sync.waiting > 0) {
                    val waiting = sync.waiting.toInt()
                    pluralStringResource(R.plurals.sync_waiting, waiting, waiting)
                } else stringResource(R.string.sync_uploaded)
            )
            // A halted group's way on is on the screen that says why (SY-15); a paused one's is the
            // menu (SY-16).
            if (sync.halted == null && !sync.paused) {
                FilledTonalButton(onClick = onSync) { Text(stringResource(R.string.sync_now)) }
            }
        }
    }
}
