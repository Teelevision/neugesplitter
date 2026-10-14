package eu.teele.neugesplitter.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.TodoChanges
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.todo.TodoMove
import eu.teele.neugesplitter.domain.todo.moved
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.TodoId
import eu.teele.neugesplitter.storage.GroupRepository
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which todos the list shows (TD-7). */
enum class TodoFilter {
    ALL,
    OPEN,

    /** Only those with a due date, the earliest first. */
    DUE,
}

/**
 * @property title the group's emoji and name; null once loaded only if this device no longer holds
 *   the group.
 * @property all the whole list in its order, but for the todo [deleting].
 * @property shown what [filter] lets through, in the order it shows them.
 * @property names every member of the group by name, removed ones too.
 * @property members who a todo can be given to: the members not removed, in the order they were
 *   added.
 * @property full whether the list holds as many todos as it may (format-spec 4.2).
 * @property deleting the todo deleted, kept out of the list while its undo is offered.
 */
data class TodoState(
    val loaded: Boolean = false,
    val title: String? = null,
    val all: List<Todo> = emptyList(),
    val shown: List<Todo> = emptyList(),
    val filter: TodoFilter = TodoFilter.ALL,
    val names: Map<MemberId, String> = emptyMap(),
    val members: List<MemberId> = emptyList(),
    val full: Boolean = false,
    val today: EntryDate? = null,
    val deleting: Todo? = null,
) {
    /** Only the order of the whole list, or of the open part of it, can be changed (TD-3). */
    val reorderable: Boolean
        get() = filter != TodoFilter.DUE
}

/**
 * What the list shows of its own changes before writing them (TD-3, TD-5): the moves in their
 * order, which todos are done or not, and a deletion.
 */
internal data class Held(
    val moves: List<TodoMove> = emptyList(),
    val done: Map<TodoId, Boolean> = emptyMap(),
    val deleted: TodoId? = null,
) {
    fun on(todos: List<Todo>): List<Todo> =
        moves
            .fold(todos, List<Todo>::moved)
            .filterNot { it.id == deleted }
            .map { todo -> done[todo.id]?.let { todo.copy(done = it) } ?: todo }

    /** A todo moved again straight after is moved once, as each move takes it out first. */
    fun moved(move: TodoMove) =
        copy(
            moves =
                if (moves.lastOrNull()?.target == move.target) moves.dropLast(1) + move
                else moves + move
        )

    /** What turns [todos], as written, into [on] them, leaving out what would change nothing. */
    fun events(todos: List<Todo>, by: MemberId, at: Instant, id: () -> EventId): List<Event> {
        fun turned(done: Boolean) =
            this.done.keys.filter { target ->
                this.done[target] == done && todos.any { it.id == target && it.done != done }
            }
        val completed = turned(true)
        val reopened = turned(false)
        return listOfNotNull(
            if (moves.fold(todos, List<Todo>::moved) == todos) null
            else Event.TodoMoved(id(), by, at, moves),
            if (completed.isEmpty()) null else Event.TodoCompleted(id(), by, at, completed),
            if (reopened.isEmpty()) null else Event.TodoReopened(id(), by, at, reopened),
            deleted
                ?.takeIf { target -> todos.any { it.id == target } }
                ?.let { Event.TodoDeleted(id(), by, at, it) },
        )
    }
}

/** [todos] as [filter] shows them. */
internal fun shown(todos: List<Todo>, filter: TodoFilter): List<Todo> =
    when (filter) {
        TodoFilter.ALL -> todos
        TodoFilter.OPEN -> todos.filterNot { it.done }
        // Stable, so that todos due on one day keep the order of the list.
        TodoFilter.DUE -> todos.filter { it.due != null }.sortedBy { it.due }
    }

/**
 * Where in the whole list [todos] a todo goes that is dropped at [index] among those [filter]
 * shows: just before the one it was dropped on, or just after the last one shown.
 */
internal fun position(todos: List<Todo>, filter: TodoFilter, target: TodoId, index: Int): Int {
    val rest = todos.filterNot { it.id == target }
    val shown = shown(rest, filter)
    return when {
        index < shown.size -> rest.indexOf(shown[index])
        shown.isEmpty() -> todos.indexOfFirst { it.id == target }
        else -> rest.indexOf(shown.last()) + 1
    }
}

/**
 * The group as last read or written, and what the list shows on top of it: [writing] is being
 * written, in order, and [held] waits for [TodoViewModel.flush].
 */
private data class Local(
    val group: Group? = null,
    val loaded: Boolean = false,
    val writing: List<Held> = emptyList(),
    val held: Held = Held(),
    val deleting: TodoId? = null,
) {
    fun batch(deletion: Boolean) = held.copy(deleted = deleting.takeIf { deletion })
}

/**
 * A group's todo list (TD-1 to TD-7). Moves and ticks are held back and written in one go when the
 * list is left, or before it changes length, since the places moved to count on it.
 *
 * @param scope outlives the list, so that what it held back is written after it is gone.
 */
class TodoViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    private val scope: CoroutineScope,
) : ViewModel() {

    private val filter = MutableStateFlow(TodoFilter.ALL)
    private val local = MutableStateFlow(Local())

    // One at a time, so that what was done first is written first.
    private val writing = Dispatchers.IO.limitedParallelism(1)

    init {
        viewModelScope.launch {
            repository.group(group).collect { replayed ->
                local.update { it.copy(group = replayed, loaded = true) }
            }
        }
    }

    val state: StateFlow<TodoState> =
        combine(local, filter) { local, filter ->
                when {
                    !local.loaded -> TodoState()
                    local.group == null -> TodoState(loaded = true)
                    else -> state(local.group, local, filter).copy(today = today())
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoState())

    fun filter(filter: TodoFilter) {
        this.filter.value = filter
    }

    /**
     * Adds a todo at the end of the list (TD-1), and shows the whole list if the filter would hide
     * it. Nothing is added with a blank [description] or to a full list.
     *
     * @return the new todo's id, or null if none was added.
     */
    fun add(description: String, due: EntryDate?, assignees: List<MemberId>): TodoId? {
        val trimmed = description.trim()
        if (trimmed.isEmpty() || state.value.full) return null
        val id = TodoId.parse(identifier())
        if (filter.value == TodoFilter.DUE && due == null) filter.value = TodoFilter.ALL
        flush()
        write { current, by ->
            if (current.todos.size >= Todo.LIMIT) emptyList()
            else listOf(Event.TodoCreated(eventId(), by, now(), Todo(id, trimmed, due, assignees)))
        }
        return id
    }

    /** Writes only the fields that changed, and nothing if none did or [description] is blank. */
    fun edit(target: TodoId, description: String, due: EntryDate?, assignees: List<MemberId>) {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return
        write { current, by ->
            val before = current.todos.firstOrNull { it.id == target } ?: return@write emptyList()
            val after = before.copy(description = trimmed, due = due, assignees = assignees)
            val changes = TodoChanges.between(before, after)
            if (changes.isEmpty) emptyList()
            else listOf(Event.TodoUpdated(eventId(), by, now(), target, changes))
        }
    }

    /** Ticks [target] done or not at once, written with what else is held back (TD-5). */
    fun done(target: TodoId, done: Boolean) {
        local.update { it.copy(held = it.held.copy(done = it.held.done + (target to done))) }
    }

    /**
     * Takes [target] out of the list at once, and writes that only once [commit] says the undo has
     * gone (TD-6), so that undoing leaves nothing in the history.
     */
    fun delete(target: TodoId) {
        commit()
        local.update { it.copy(deleting = target) }
    }

    fun undo() {
        local.update { it.copy(deleting = null) }
    }

    /** Writes what is held back, and then the deletion waiting for its undo, if there is one. */
    fun commit() = flush(deletion = true)

    /** Writes what is held back in one go; with [deletion], the deletion waiting too. */
    fun flush(deletion: Boolean = false) {
        val batch = local.getAndUpdate { it.flushed(deletion) }.batch(deletion)
        if (batch == Held()) return
        write({ it.copy(writing = it.writing.drop(1)) }) { current, by ->
            batch.events(current.todos, by, now(), ::eventId)
        }
    }

    /**
     * Moves [target] to [index] among the todos shown (TD-3), held back as a place in the whole
     * list. A deletion waiting for its undo is written first, since the place is counted without
     * it.
     *
     * @return false if that is where it already is.
     */
    fun move(target: TodoId, index: Int): Boolean {
        val current = state.value
        val from = current.all.indexOfFirst { it.id == target }
        if (from < 0 || !current.reorderable) return false
        val to = position(current.all, current.filter, target, index)
        if (to == from) return false
        if (local.value.deleting != null) commit()
        local.update { it.copy(held = it.held.moved(TodoMove(target, to))) }
        return true
    }

    override fun onCleared() {
        commit()
    }

    private fun Local.flushed(deletion: Boolean): Local {
        val batch = batch(deletion)
        if (batch == Held()) return this
        return copy(
            writing = writing + batch,
            held = Held(),
            deleting = deleting.takeUnless { deletion },
        )
    }

    private fun eventId() = EventId.parse(identifier())

    /**
     * Appends what [events] makes of the group as it is now, if anything, then shows the group
     * written, with [after] done to what is shown on top of it.
     */
    private fun write(
        after: (Local) -> Local = { it },
        events: (current: Group, by: MemberId) -> List<Event>,
    ) {
        scope.launch(writing) {
            var written: Group? = null
            try {
                val by = repository.actingAs(group) ?: return@launch
                val current = repository.load(group) ?: return@launch
                val appended = events(current, by)
                if (appended.isNotEmpty()) written = repository.append(group, appended, today())
            } finally {
                local.update { after(it).copy(group = written ?: it.group) }
            }
        }
    }
}

private fun state(group: Group, local: Local, filter: TodoFilter): TodoState {
    val todos = (local.writing + local.held).fold(group.todos) { list, held -> held.on(list) }
    val listed = todos.filterNot { it.id == local.deleting }
    return TodoState(
        loaded = true,
        title = listOfNotNull(group.emoji, group.name).joinToString(" "),
        all = listed,
        shown = shown(listed, filter),
        filter = filter,
        names = group.members.mapValues { it.value.name },
        members = group.members.keys.filterNot { it in group.removed },
        full = todos.size >= Todo.LIMIT,
        deleting = todos.firstOrNull { it.id == local.deleting },
    )
}
