package eu.teele.neugesplitter.ui.todo

import android.icu.text.ListFormatter
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.TodoId
import eu.teele.neugesplitter.ui.DeleteAction
import eu.teele.neugesplitter.ui.SWIPE_ACTION
import eu.teele.neugesplitter.ui.SwipeToReveal
import eu.teele.neugesplitter.ui.editor.DatePick
import eu.teele.neugesplitter.ui.history.mediumDate
import eu.teele.neugesplitter.ui.theme.largeText
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.launch

private const val TODO = "todo"

/** Pixels a frame the list scrolls at most while a todo is dragged to its edge. */
private const val SCROLL = 24f

/**
 * A group's todo list (TD-1 to TD-7): dragged into order by the handle on the left, swiped for done
 * and delete, tapped to edit.
 *
 * @param newRequested the plus in the navigation bar was pressed; [onNewTaken] confirms it.
 * @param onBack returns to the overview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    viewModel: TodoViewModel,
    newRequested: Boolean,
    onNewTaken: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val full = stringResource(R.string.todo_full)
    val deleted = stringResource(R.string.todo_deleted)
    val undo = stringResource(R.string.undo)
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Todo?>(null) }
    var added by remember { mutableStateOf<TodoId?>(null) }

    LaunchedEffect(state) { if (state.loaded && state.title == null) onBack() }
    LaunchedEffect(newRequested) {
        if (!newRequested) return@LaunchedEffect
        if (state.full) scope.launch { snackbar.showSnackbar(full) } else creating = true
        onNewTaken()
    }
    LaunchedEffect(state.deleting?.id) {
        if (state.deleting == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(deleted, undo, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.commit()
    }
    // Leaving the list takes the undo with it.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.commit() }
    }
    // The process may not come back to write what the list holds back.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.todos)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    FilterButton(
                        TodoFilter.ALL,
                        state.filter,
                        R.drawable.ic_list,
                        R.string.todo_filter_all,
                        viewModel::filter,
                    )
                    FilterButton(
                        TodoFilter.OPEN,
                        state.filter,
                        R.drawable.ic_check_box_outline_blank,
                        R.string.todo_filter_open,
                        viewModel::filter,
                    )
                    FilterButton(
                        TodoFilter.DUE,
                        state.filter,
                        R.drawable.ic_event,
                        R.string.todo_filter_due,
                        viewModel::filter,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Todos(
            state,
            viewModel,
            locale,
            added,
            onScrolled = { added = null },
            onEdit = { editing = it },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    val today = state.today
    if (today != null && (creating || editing != null)) {
        val todo = editing
        key(todo?.id) {
            TodoSheet(
                todo,
                state,
                today,
                locale,
                onSave = { description, due, assignees ->
                    if (todo == null) added = viewModel.add(description, due, assignees)
                    else viewModel.edit(todo.id, description, due, assignees)
                    creating = false
                    editing = null
                },
                onDismiss = {
                    creating = false
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun FilterButton(
    filter: TodoFilter,
    selected: TodoFilter,
    @DrawableRes icon: Int,
    @StringRes label: Int,
    onSelect: (TodoFilter) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    IconToggleButton(
        checked = filter == selected,
        onCheckedChange = { onSelect(filter) },
        colors =
            IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = colours.primaryContainer,
                checkedContentColor = colours.onPrimaryContainer,
            ),
    ) {
        Icon(painterResource(icon), stringResource(label))
    }
}

@Composable
private fun Todos(
    state: TodoState,
    viewModel: TodoViewModel,
    locale: Locale,
    added: TodoId?,
    onScrolled: () -> Unit,
    onEdit: (Todo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val reorder = remember(list) { Reorder(list) }
    val todos = reorder.order ?: state.shown
    val current by rememberUpdatedState(todos)
    val edge = with(LocalDensity.current) { 64.dp.toPx() }

    // What was dropped stays where it was until the list shows it moved.
    LaunchedEffect(state.shown) { if (reorder.dragged == null) reorder.order = null }
    LaunchedEffect(reorder.dragged) {
        if (reorder.dragged == null) return@LaunchedEffect
        while (true) {
            withFrameNanos {}
            val by = reorder.overscroll(edge)
            if (by != 0f) {
                list.scrollBy(by)
                reorder.swap()
            }
        }
    }
    // A todo added goes to the end of the list, which comes into view (TD-1).
    LaunchedEffect(added, todos) {
        val index = todos.indexOfFirst { it.id == added }
        if (index < 0) return@LaunchedEffect
        list.animateScrollToItem(index)
        onScrolled()
    }
    val drop = {
        reorder.end()?.let { (id, index) -> if (!viewModel.move(id, index)) reorder.order = null }
    }

    LazyColumn(state = list, modifier = modifier) {
        if (state.loaded && todos.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(
                        if (state.all.isEmpty()) R.string.todo_empty
                        else R.string.todo_empty_filtered
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(todos, key = { it.id.hex }, contentType = { TODO }) { todo ->
            val dragging = reorder.dragged == todo.id
            Box(
                if (dragging) {
                    Modifier.zIndex(1f).graphicsLayer {
                        translationY = reorder.translation
                        shadowElevation = 8.dp.toPx()
                    }
                } else Modifier.animateItem()
            ) {
                SwipeToReveal(
                    SWIPE_ACTION * 2,
                    actions = { close ->
                        DoneAction(todo.done) { viewModel.done(todo.id, it) }
                        DeleteAction {
                            close()
                            viewModel.delete(todo.id)
                        }
                    },
                ) {
                    TodoRow(
                        todo,
                        state.names,
                        locale,
                        Modifier.background(MaterialTheme.colorScheme.surface).clickable {
                            onEdit(todo)
                        },
                        handle =
                            if (!state.reorderable) null
                            else
                                Modifier.pointerInput(todo.id) {
                                    detectDragGestures(
                                        onDragStart = { reorder.start(todo.id, current) },
                                        onDragEnd = { drop() },
                                        onDragCancel = { drop() },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            reorder.drag(amount.y)
                                        },
                                    )
                                },
                    )
                }
            }
        }
    }
}

/** A todo dragged within [list]; its new place shows in [order] until it is dropped. */
@Stable
private class Reorder(private val list: LazyListState) {
    var order by mutableStateOf<List<Todo>?>(null)
    var dragged by mutableStateOf<TodoId?>(null)
        private set

    private var start = 0
    private var delta by mutableFloatStateOf(0f)

    /** How far the dragged todo is drawn from where the list lays it out. */
    val translation: Float
        get() = slot()?.let { start + delta - it.offset } ?: 0f

    private fun slot(): LazyListItemInfo? =
        dragged?.let { id -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id.hex } }

    fun start(id: TodoId, todos: List<Todo>) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id.hex } ?: return
        order = todos
        dragged = id
        start = item.offset
        delta = 0f
    }

    fun drag(by: Float) {
        delta += by
        swap()
    }

    /** Swaps the dragged todo with the one under its middle. */
    fun swap() {
        val item = slot() ?: return
        val current = order ?: return
        val from = current.indexOfFirst { it.id == dragged }
        // Until the list lays out the last swap, its places are not the order's.
        if (item.index != from) return
        val middle = start + delta + item.size / 2f
        val target =
            list.layoutInfo.visibleItemsInfo.firstOrNull {
                it.key != item.key &&
                    it.contentType == TODO &&
                    middle >= it.offset &&
                    middle < it.offset + it.size
            } ?: return
        val to = current.indexOfFirst { it.id.hex == target.key }
        // Keeps the list from following the first item it shows when that one moves.
        if (from == list.firstVisibleItemIndex || to == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        order = current.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** How far to scroll this frame, as the dragged todo nears an edge it is moving to. */
    fun overscroll(edge: Float): Float {
        val item = slot() ?: return 0f
        val info = list.layoutInfo
        val top = start + delta
        val bottom = top + item.size
        val above = info.viewportStartOffset + edge - top
        val below = bottom - (info.viewportEndOffset - edge)
        return when {
            delta < 0 && above > 0 -> -min(above, edge) / edge * SCROLL
            delta > 0 && below > 0 -> min(below, edge) / edge * SCROLL
            else -> 0f
        }
    }

    /** The todo dropped and where among those shown, if one was dragged. */
    fun end(): Pair<TodoId, Int>? {
        val id = dragged ?: return null
        dragged = null
        delta = 0f
        return id to (order ?: return null).indexOfFirst { it.id == id }
    }
}

/** Ticks a todo done or open again, behind its row (TD-5). */
@Composable
private fun DoneAction(done: Boolean, onDone: (Boolean) -> Unit) {
    val colours = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier.width(SWIPE_ACTION)
                .fillMaxHeight()
                .background(colours.surfaceContainerHighest)
                .toggleable(done, role = Role.Checkbox, onValueChange = onDone),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Checkbox(checked = done, onCheckedChange = null)
        Text(
            stringResource(R.string.todo_done),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * A todo the height of an entry: the handle it is dragged by, its description, struck through once
 * done (TD-5), who it is assigned to and when it is due. [handle] is null where it cannot be
 * dragged.
 */
@Composable
fun TodoRow(
    todo: Todo,
    names: Map<MemberId, String>,
    locale: Locale,
    modifier: Modifier = Modifier,
    handle: Modifier? = null,
) {
    val colours = MaterialTheme.colorScheme
    val stacked = largeText()
    val doneLabel = stringResource(R.string.todo_done)
    val assignees =
        ListFormatter.getInstance(locale).format(todo.assignees.map { names[it].orEmpty() })

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                (handle ?: Modifier)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colours.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_drag_indicator),
                if (handle == null) null else stringResource(R.string.todo_drag),
                tint = colours.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(
                todo.description,
                style = MaterialTheme.typography.titleMedium,
                color = if (todo.done) colours.onSurfaceVariant else colours.onSurface,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                maxLines = if (stacked) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { if (todo.done) stateDescription = doneLabel },
            )
            // Kept when empty, so that every row is as high as an entry's.
            Text(
                assignees,
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
                maxLines = if (stacked) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stacked) Due(todo.due, locale)
        }
        if (!stacked) Due(todo.due, locale)
    }
}

@Composable
private fun Due(due: EntryDate?, locale: Locale) {
    if (due == null) return
    Text(
        mediumDate(due, locale),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Writes a new todo, or edits [todo] (TD-1, TD-2, TD-4). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TodoSheet(
    todo: Todo?,
    state: TodoState,
    today: EntryDate,
    locale: Locale,
    onSave: (description: String, due: EntryDate?, assignees: List<MemberId>) -> Unit,
    onDismiss: () -> Unit,
) {
    var description by rememberSaveable { mutableStateOf(todo?.description.orEmpty()) }
    var due by remember { mutableStateOf(todo?.due) }
    var assignees by remember { mutableStateOf(todo?.assignees.orEmpty().toSet()) }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Someone removed from the group can still be taken off a todo.
    val offered = state.members + todo?.assignees.orEmpty().filterNot { it in state.members }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (todo == null) R.string.todo_new else R.string.todo_edit),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.entry_description)) },
                keyboardOptions =
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { picking = true },
                    label = {
                        Text(
                            due?.let {
                                stringResource(R.string.todo_due_on, mediumDate(it, locale))
                            } ?: stringResource(R.string.todo_due_none)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_event),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                if (due != null) {
                    IconButton(onClick = { due = null }) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            stringResource(R.string.todo_due_clear),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.todo_assignees),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offered.forEach { member ->
                    val selected = member in assignees
                    FilterChip(
                        selected = selected,
                        onClick = {
                            assignees = if (selected) assignees - member else assignees + member
                        },
                        label = { Text(state.names[member].orEmpty()) },
                        leadingIcon =
                            if (selected) {
                                {
                                    Icon(
                                        painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else null,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { onSave(description, due, offered.filter { it in assignees }) },
                    enabled = description.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        LaunchedEffect(Unit) { if (todo == null) focus.requestFocus() }
    }
    if (picking) {
        DatePick(
            due ?: today,
            onPicked = {
                due = it
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}
