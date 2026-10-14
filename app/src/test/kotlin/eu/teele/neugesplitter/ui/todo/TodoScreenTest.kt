package eu.teele.neugesplitter.ui.todo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.todo.TodoMove
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.TodoId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class TodoScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var issued = 0
    private var requested by mutableStateOf(false)
    private var showing by mutableStateOf(true)
    private lateinit var viewModel: TodoViewModel

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun todo(n: Int, due: String? = null, assignees: List<MemberId> = emptyList()) =
        Todo(TodoId.parse(id(0x40 + n)), "Todo $n", due?.let(EntryDate::parse), assignees)

    /** Anna's group "Trip" holding [todos], in that order. */
    private fun show(vararg todos: Todo, done: List<Todo> = emptyList()) {
        groups.create(
            Group.create(trip, "Trip", null, "🏖", null, listOf(anna, ben), at, ::eventId),
            today,
        )
        groups.actAs(trip, anna.id)
        if (todos.isNotEmpty()) {
            groups.append(
                trip,
                todos.map { Event.TodoCreated(eventId(), anna.id, at, it) } +
                    done.map { Event.TodoCompleted(eventId(), anna.id, at, listOf(it.id)) },
                today,
            )
        }
        viewModel =
            TodoViewModel(
                groups,
                trip,
                { id(0x200 + issued++) },
                { today },
                { at },
                CoroutineScope(Dispatchers.IO),
            )
        compose.setContent {
            NeugesplitterTheme {
                if (showing) {
                    TodoScreen(
                        viewModel,
                        newRequested = requested,
                        onNewTaken = { requested = false },
                        onBack = {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) { viewModel.state.value.loaded }
    }

    private fun leave() {
        showing = false
        compose.waitForIdle()
    }

    private fun stored() = groups.load(trip)!!.todos

    private fun shown() = viewModel.state.value.all

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun top(text: String) =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun waitFor(text: String) = compose.waitUntil(5_000) { count(text) > 0 }

    @Test
    fun `an empty list says how to add the first todo (TD-1)`() {
        show()
        compose.onNodeWithText("Nothing to do yet. The plus button adds a to-do.").assertExists()
    }

    @Test
    fun `the plus adds a todo at the end of the list, which scrolls to it (TD-1, TD-4)`() {
        show(*Array(30) { todo(it) })
        requested = true
        waitFor("New to-do")
        compose.onNodeWithText("Description").performTextInput("  Book the ferry ")
        compose.onNodeWithText("Ben").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { stored().size == 31 }
        val added = stored().last()
        assertEquals("Book the ferry", added.description)
        assertEquals(listOf(ben.id), added.assignees)
        assertEquals(null, added.due)
        waitFor("Book the ferry")
        compose.onNodeWithText("Book the ferry").assertIsDisplayed()
    }

    @Test
    fun `a todo is edited in the sheet, which writes only what changed (TD-2)`() {
        show(todo(1, "2026-10-09", listOf(anna.id)))
        compose.onNodeWithText("Todo 1").performClick()
        waitFor("Edit to-do")
        compose.onNodeWithText("Due Oct 9, 2026").assertExists()
        compose.onNodeWithContentDescription("Remove due date").performClick()
        compose.onNodeWithText("No due date").assertExists()
        compose.onNodeWithText("Ben").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { stored().single().due == null }
        assertEquals(listOf(anna.id, ben.id), stored().single().assignees)
        val written = groups.events(trip).last() as Event.TodoUpdated
        assertEquals(Change.Unchanged, written.todo.description)
        waitFor("Anna and Ben")
    }

    @Test
    fun `a todo shows who it is assigned to below it and its due date beside it (TD-1)`() {
        show(todo(1, "2026-10-09", listOf(anna.id, ben.id)))
        compose.onNodeWithText("Anna and Ben").assertExists()
        compose.onNodeWithText("Oct 9, 2026").assertExists()
        assertTrue(top("Todo 1") < top("Anna and Ben"))
    }

    @Test
    fun `swiping a todo aside ticks it done at once, written on leaving the list (TD-5)`() {
        show(todo(1), todo(2))
        val before = groups.events(trip).size
        compose.onNodeWithText("Todo 1").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { shown().first().done }
        compose.onNodeWithText("Todo 1").assertExists()
        // Ticked off and on again where it is, which is held back too.
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { !shown().first().done }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { shown().first().done }
        assertTrue(stored().none { it.done })

        leave()
        compose.waitUntil(5_000) { stored().first().done }
        assertEquals(
            listOf(Event.TodoCompleted(EventId.parse(id(0x200)), anna.id, at, listOf(todo(1).id))),
            groups.events(trip).drop(before),
        )
    }

    @Test
    fun `what the list holds back is written before a deletion, in one event each (TD-5, TD-6)`() {
        show(todo(1), todo(2), todo(3))
        viewModel.done(todo(1).id, true)
        viewModel.done(todo(3).id, true)
        viewModel.done(todo(2).id, true)
        viewModel.done(todo(2).id, false)
        viewModel.delete(todo(3).id)
        compose.waitUntil(5_000) { stored().count { it.done } == 2 }
        viewModel.commit()
        compose.waitUntil(5_000) { stored().size == 2 }
        val written = groups.events(trip).takeLast(2)
        assertEquals(listOf(todo(1).id, todo(3).id), (written[0] as Event.TodoCompleted).targets)
        assertEquals(todo(3).id, (written[1] as Event.TodoDeleted).target)
    }

    @Test
    fun `swiping a todo aside deletes it once the undo has gone (TD-6)`() {
        show(todo(1), todo(2))
        compose.onNodeWithText("Todo 2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete").performClick()
        waitFor("To-do deleted")
        assertEquals(0, count("Todo 2"))
        compose.onNodeWithText("Undo").performClick()
        waitFor("Todo 2")
        assertEquals(2, stored().size)

        compose.onNodeWithText("Todo 2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.mainClock.advanceTimeBy(11_000)
        compose.waitUntil(5_000) { stored().size == 1 }
        assertTrue(groups.events(trip).last() is Event.TodoDeleted)
    }

    @Test
    fun `the filters show all, those not done, or those due, earliest first (TD-7)`() {
        val first = todo(1, "2026-10-20")
        val second = todo(2)
        val third = todo(3, "2026-10-10")
        show(first, second, third, done = listOf(third))

        compose.onNodeWithContentDescription("Show not done").performClick()
        compose.waitUntil(5_000) { count("Todo 3") == 0 }
        compose.onNodeWithText("Todo 1").assertExists()
        compose.onNodeWithText("Todo 2").assertExists()

        compose
            .onNodeWithContentDescription("Show those with a due date, earliest first")
            .performClick()
        compose.waitUntil(5_000) { count("Todo 2") == 0 }
        assertTrue(top("Todo 3") < top("Todo 1"))
        // Dragging the list into an order of its own is only for the whole list.
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription("Drag to reorder").fetchSemanticsNodes().size,
        )

        compose.onNodeWithContentDescription("Show all").performClick()
        compose.waitUntil(5_000) { count("Todo 2") == 1 }
        assertTrue(top("Todo 1") < top("Todo 2"))
    }

    @Test
    fun `todos dragged by their handles move there, written in one event on leaving (TD-3)`() {
        show(todo(1), todo(2), todo(3), todo(4))
        val before = groups.events(trip).size
        drag(3)
        compose.waitUntil(5_000) { order(shown()) == listOf(1, 4, 2, 3) }
        drag(3)
        compose.waitUntil(5_000) { order(shown()) == listOf(1, 3, 4, 2) }
        assertEquals(listOf(1, 2, 3, 4), order(stored()))

        leave()
        compose.waitUntil(5_000) { order(stored()) == listOf(1, 3, 4, 2) }
        val written = groups.events(trip).drop(before).single() as Event.TodoMoved
        assertEquals(listOf(TodoMove(todo(4).id, 1), TodoMove(todo(3).id, 1)), written.moves)
    }

    private fun order(todos: List<Todo>) =
        todos.map { it.description.removePrefix("Todo ").toInt() }

    /** Drags the todo at [index] up by a little more than two rows. */
    private fun drag(index: Int) {
        val handles =
            compose.onAllNodesWithContentDescription("Drag to reorder", useUnmergedTree = true)
        val step =
            handles[1].fetchSemanticsNode().boundsInRoot.top -
                handles[0].fetchSemanticsNode().boundsInRoot.top
        // A drag watches every frame for the edge, so the clock goes frame by frame meanwhile.
        compose.mainClock.autoAdvance = false
        handles[index].performTouchInput { down(center) }
        repeat(30) {
            handles[index].performTouchInput { moveBy(Offset(0f, -step * 2.3f / 30)) }
            compose.mainClock.advanceTimeByFrame()
        }
        handles[index].performTouchInput { up() }
        compose.mainClock.autoAdvance = true
    }
}
