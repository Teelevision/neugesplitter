package eu.teele.neugesplitter.domain.todo

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.date
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.TodoChanges
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.history.Happened
import eu.teele.neugesplitter.domain.history.TodoChange
import eu.teele.neugesplitter.domain.history.TodosChange
import eu.teele.neugesplitter.domain.history.history
import eu.teele.neugesplitter.domain.member
import eu.teele.neugesplitter.domain.todo
import eu.teele.neugesplitter.domain.todoId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TodoTest {

    private fun listed() =
        Log().create().add(ANNA, "Anna").add(BEN, "Ben").todo(todo(1)).todo(todo(2)).todo(todo(3))

    private fun Group.order() = todos.map { it.id }

    @Test
    fun `a new todo goes to the end of the list (TD-1)`() {
        assertEquals(listOf(todoId(1), todoId(2), todoId(3)), listed().replay().order())
    }

    @Test
    fun `an update changes only the fields it names, and null clears (TD-2)`() {
        val group =
            listed()
                .update(
                    todoId(2),
                    TodoChanges(
                        due = Change.To(date("2026-09-01")),
                        assignees = Change.To(listOf(BEN)),
                    ),
                )
                .update(todoId(2), TodoChanges(description = Change.To("Call the landlord")))
                .update(todoId(2), TodoChanges(due = Change.To(null)))
                .replay()
        assertEquals(
            Todo(todoId(2), "Call the landlord", null, listOf(BEN)),
            group.todos.single { it.id == todoId(2) },
        )
    }

    @Test
    fun `a move puts the todo at the position it names, counted from 0 (TD-3)`() {
        val group = listed().move(todoId(3), 0).move(todoId(1), 1).replay()
        assertEquals(listOf(todoId(3), todoId(1), todoId(2)), group.order())
    }

    @Test
    fun `two moves to the top end in log order, the later one on top (TD-3)`() {
        val group = listed().move(todoId(2), 0).move(todoId(3), 0).replay()
        assertEquals(listOf(todoId(3), todoId(2), todoId(1)), group.order())
    }

    @Test
    fun `the moves of one event apply in their order, as separate events would (TD-3)`() {
        val moves = arrayOf(TodoMove(todoId(2), 0), TodoMove(todoId(3), 0), TodoMove(todoId(2), 2))
        val separate = moves.fold(listed()) { log, move -> log.moves(move) }.replay()
        val one = listed().moves(*moves).replay()
        assertEquals(listOf(todoId(3), todoId(1), todoId(2)), one.order())
        assertEquals(separate.todos, one.todos)
    }

    @Test
    fun `a move of a deleted todo is skipped, the others in its event still apply (TD-6)`() {
        val group =
            listed()
                .delete(todoId(1))
                .moves(TodoMove(todoId(1), 0), TodoMove(todoId(3), 0))
                .replay()
        assertEquals(listOf(todoId(3), todoId(2)), group.order())
    }

    @Test
    fun `a position beyond the end of the list moves the todo to the end (TD-3)`() {
        val group = listed().move(todoId(1), 9_999).replay()
        assertEquals(listOf(todoId(2), todoId(3), todoId(1)), group.order())
    }

    @Test
    fun `a negative position, or no move at all, is malformed (format-spec 4_2)`() {
        assertFailsWith<Malformed> { listed().move(todoId(1), -1).replay() }
        assertFailsWith<Malformed> {
            listed().moves(TodoMove(todoId(1), 0), TodoMove(todoId(2), -1)).replay()
        }
        assertFailsWith<Malformed> { listed().moves().replay() }
    }

    @Test
    fun `a done todo stays where it is in the list, and can be reopened (TD-5)`() {
        val done = listed().complete(todoId(2)).replay()
        assertEquals(listOf(false, true, false), done.todos.map { it.done })
        val reopened = listed().complete(todoId(2)).reopen(todoId(2)).replay()
        assertTrue(reopened.todos.none { it.done })
    }

    @Test
    fun `one event completes or reopens several todos, and must name one (TD-5)`() {
        val group =
            listed()
                .complete(listOf(todoId(1), todoId(3)))
                .reopen(listOf(todoId(3), todoId(2)))
                .replay()
        assertEquals(listOf(true, false, false), group.todos.map { it.done })
        assertFailsWith<Malformed> { listed().complete(emptyList()).replay() }
        assertFailsWith<Malformed> { listed().reopen(emptyList()).replay() }
    }

    @Test
    fun `a deleted todo leaves the list, and what names it later has no effect (TD-6)`() {
        val deleted = listed().delete(todoId(2)).replay()
        assertEquals(listOf(todoId(1), todoId(3)), deleted.order())

        val raced =
            listed()
                .delete(todoId(2))
                .delete(todoId(2))
                .move(todoId(2), 0)
                .complete(todoId(2))
                .update(todoId(2), TodoChanges(description = Change.To("Back")))
                .replay()
        assertEquals(deleted.todos, raced.todos)
    }

    @Test
    fun `an event naming a todo the group never had is malformed (format-spec 4_2)`() {
        assertFailsWith<Malformed> { listed().complete(todoId(9)).replay() }
        assertFailsWith<Malformed> { listed().move(todoId(9), 0).replay() }
        assertFailsWith<Malformed> { listed().delete(todoId(9)).replay() }
    }

    @Test
    fun `a todo is not created twice, not even after its deletion (format-spec 4_2)`() {
        assertFailsWith<Malformed> { listed().todo(todo(1)).replay() }
        assertFailsWith<Malformed> { listed().delete(todoId(1)).todo(todo(1)).replay() }
    }

    @Test
    fun `assignees are members of the group, removed ones included (TD-4)`() {
        val stranger = member(9)
        assertFailsWith<Malformed> {
            listed().todo(Todo(todoId(4), "Shop", assignees = listOf(stranger))).replay()
        }
        assertFailsWith<Malformed> {
            listed()
                .update(todoId(1), TodoChanges(assignees = Change.To(listOf(stranger))))
                .replay()
        }
        val group =
            listed().remove(BEN).todo(Todo(todoId(4), "Shop", assignees = listOf(BEN))).replay()
        assertEquals(listOf(BEN), group.todos.last().assignees)
    }

    @Test
    fun `waiting todo events always rebase, whatever was written meanwhile (TD-3, TD-6)`() {
        val from = listed().replay()
        val mine = Log().move(todoId(3), 0).complete(todoId(2)).events()
        val theirs = listed().delete(todoId(2)).delete(todoId(3)).replay()
        val rebased = Group.rebase(mine, from, theirs)
        assertEquals(mine, rebased)
        assertEquals(listOf(todoId(1)), rebased.fold(theirs, Group::plus).order())
    }

    @Test
    fun `the history tells what happened to which todo (HI-2)`() {
        val events =
            listed()
                .update(todoId(1), TodoChanges(description = Change.To("Shop")))
                .delete(todoId(1))
                .events()
        val told = history(events).map { it.happened }.filterIsInstance<Happened.Todo>()
        assertEquals(
            listOf("Todo 1", "Todo 2", "Todo 3", "Shop", "Shop"),
            told.map { it.description },
        )
        assertEquals(
            listOf(
                TodoChange.Created,
                TodoChange.Created,
                TodoChange.Created,
                TodoChange.Edited(todo(1), todo(1, "Shop")),
                TodoChange.Deleted,
            ),
            told.map { it.change },
        )
    }

    @Test
    fun `the history tells which todos one event changed, each once (HI-2, TD-3, TD-5)`() {
        val events =
            listed()
                .delete(todoId(1))
                .moves(TodoMove(todoId(3), 0), TodoMove(todoId(1), 0), TodoMove(todoId(3), 1))
                .complete(listOf(todoId(2), todoId(3)))
                .reopen(todoId(3))
                .events()
        assertEquals(
            listOf(
                Happened.Todos(TodosChange.MOVED, listOf("Todo 3", "Todo 1")),
                Happened.Todos(TodosChange.COMPLETED, listOf("Todo 2", "Todo 3")),
                Happened.Todos(TodosChange.REOPENED, listOf("Todo 3")),
            ),
            history(events).takeLast(3).map { it.happened },
        )
    }
}
