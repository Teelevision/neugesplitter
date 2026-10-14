package eu.teele.neugesplitter.ui.todo

import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.value.TodoId
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoPositionTest {

    private fun todo(n: Int, done: Boolean = false) =
        Todo(TodoId.parse("%032x".format(n)), "Todo $n", done = done)

    private val a = todo(1)
    private val b = todo(2, done = true)
    private val c = todo(3)
    private val d = todo(4, done = true)
    private val e = todo(5)
    private val all = listOf(a, b, c, d, e)

    @Test
    fun `in the whole list the place shown is the place written (TD-3)`() {
        assertEquals(0, position(all, TodoFilter.ALL, e.id, 0))
        assertEquals(4, position(all, TodoFilter.ALL, a.id, 4))
    }

    @Test
    fun `among those not done a todo goes just before the one it was dropped on (TD-3)`() {
        // a c e shown; e dropped first goes before a.
        assertEquals(0, position(all, TodoFilter.OPEN, e.id, 0))
        // e dropped second goes before c, past the done b.
        assertEquals(2, position(all, TodoFilter.OPEN, e.id, 1))
        // a dropped last goes just after e, the last one shown.
        assertEquals(4, position(all, TodoFilter.OPEN, a.id, 2))
        // a dropped between c and e goes before e, after the done d.
        assertEquals(3, position(all, TodoFilter.OPEN, a.id, 1))
    }
}
