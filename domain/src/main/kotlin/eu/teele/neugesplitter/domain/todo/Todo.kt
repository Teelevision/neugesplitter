package eu.teele.neugesplitter.domain.todo

import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.TodoId
import kotlinx.collections.immutable.toPersistentList

/**
 * One item of a group's todo list (TD-1): what is to be done, by when, and by whom. A done one
 * stays in the list (TD-5).
 */
data class Todo(
    val id: TodoId,
    val description: String,
    val due: EntryDate? = null,
    val assignees: List<MemberId> = emptyList(),
    val done: Boolean = false,
) {
    companion object {
        /** How many todos a list may hold before a writer stops adding to it (format-spec 4.2). */
        const val LIMIT = 10_000
    }
}

/** Puts [target] at [position] in the list without it, counted from 0, or last if beyond (TD-3). */
data class TodoMove(val target: TodoId, val position: Int)

/** This list with [move] applied, the same if it does not hold the todo moved. */
fun List<Todo>.moved(move: TodoMove): List<Todo> {
    val index = indexOfFirst { it.id == move.target }
    if (index < 0) return this
    val rest = toPersistentList().removeAt(index)
    return rest.add(minOf(move.position, rest.size), this[index])
}
