package eu.teele.neugesplitter.ui.activity

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.domain.value.TodoId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.storage.settings.Failure
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import eu.teele.neugesplitter.ui.receive
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import eu.teele.neugesplitter.ui.todo.TodoViewModel
import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class ActivityScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private val zone = TimeZone.getDefault()
    private val edited = mutableListOf<EntryId>()
    private val steps = mutableListOf<EventId>()
    private val synced = mutableListOf<GroupId>()
    private var shares = 0
    private var problems = 0
    private var todoLists = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))
    private val dinnerId = EntryId.parse(id(10))
    private val taxiId = EntryId.parse(id(11))
    private val cleaning = SeriesId.parse(id(20))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = TimeZone.setDefault(zone)

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun entry(id: EntryId, description: String) =
        Entry(
            id = id,
            kind = Kind.EXPENSE,
            description = description,
            date = today,
            amount = Amount.ofUnits(30_000_000L),
            payers = Side.Equal(listOf(anna.id)),
            beneficiaries = Side.Equal(listOf(anna.id, ben.id)),
        )

    private fun hours(n: Long) = at.plusSeconds(n * 3600)

    /** Ben's cleaning, suggested on the first of every month since September (RE-3). */
    private fun suggesting() {
        val template =
            Template.of(entry(dinnerId, "Cleaning")).copy(payers = Side.Equal(listOf(ben.id)))
        val monthly = Segment(Interval.MonthlyOn(setOf(1)), Mode.SUGGESTED, template)
        val september = EntryDate.parse("2026-09-01")
        groups.append(
            trip,
            listOf(Event.SeriesCreated(eventId(), ben.id, at, cleaning, september, monthly)),
            today,
        )
    }

    private fun pending() = groups.load(trip)!!.occurrences(today).filter { it.pending }

    /** Anna's group "Trip", where Ben added a dinner after an hour, read two hours in. */
    private fun show(before: () -> Unit = {}) {
        groups.create(
            Group.create(trip, "Trip", null, "🏖", null, listOf(anna, ben), at, ::eventId),
            today,
        )
        groups.append(
            trip,
            listOf(Event.EntryCreated(eventId(), ben.id, hours(1), entry(dinnerId, "Dinner"))),
            today,
        )
        before()
        val viewModel =
            ActivityViewModel(groups, trip, { today }, { hours(2) }, { synced += it }, {})
        val todos =
            TodoViewModel(
                groups,
                trip,
                { id(0x200 + events++) },
                { today },
                { hours(2) },
                CoroutineScope(Dispatchers.IO),
            )
        compose.setContent {
            NeugesplitterTheme {
                ActivityScreen(
                    viewModel,
                    todos,
                    onBack = {},
                    onEdit = { edited += it },
                    onStep = { steps += it },
                    onShare = { shares++ },
                    onSyncProblem = { problems++ },
                    onTodos = { todoLists++ },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("🏖 Trip").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun share() {
        groups.backends.connect(
            trip,
            BackendSettings("https://s3.example", "splits", "", "auto", false, "AK"),
        )
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    // History lines name to-dos too, but say what was done to them.
    private fun todoRows(text: String) =
        compose
            .onAllNodes(
                hasText(text, substring = true)
                    .and(hasClickAction())
                    .and(hasText("to-do", substring = true).not())
            )
            .fetchSemanticsNodes()
            .size

    private fun unseen() =
        compose.onAllNodesWithContentDescription("New").fetchSemanticsNodes().size

    private fun top(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().first().boundsInRoot.top

    private fun swipeSnackbarAway(message: String) {
        val bounds = compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(root.width * 0.9f, bounds.center.y),
                end = Offset(root.width * 0.1f, bounds.center.y),
            )
        }
    }

    @Test
    fun `every change is listed newest first under its day, with who made it and when (HI-2)`() {
        show()
        val created = "Anna created the group"
        val dinner = "Ben added 'Dinner'"
        compose.onNodeWithText(created).assertExists()
        compose.onNodeWithText("Anna added Ben").assertExists()
        compose.onNodeWithText(dinner).assertExists()
        compose.onNodeWithText("1:00", substring = true).assertExists()
        assertTrue(top("Wednesday, October 7, 2026") < top(dinner))
        assertTrue(top(dinner) < top(created))
        assertEquals(0, count("Suggestions"))
    }

    @Test
    fun `an edit names the fields it changed, cut off where they do not fit (HI-2, ui-design 4)`() {
        show {
            groups.append(
                trip,
                listOf(
                    Event.EntryUpdated(
                        eventId(),
                        anna.id,
                        hours(2),
                        dinnerId,
                        EntryChanges(
                            description = Change.To("Supper"),
                            categoryId = Change.To(BuiltInCategory.DINING_AND_CAFES.id),
                        ),
                    )
                ),
                today,
            )
        }
        compose.onNodeWithText("Anna edited 'Supper'").assertExists()
        compose.onNodeWithText("Changed: Description, Category").assertExists()
        val row = compose.onNodeWithText("Anna edited 'Supper'").fetchSemanticsNode()
        val other = compose.onNodeWithText("Ben added 'Dinner'").fetchSemanticsNode()
        assertEquals(other.size.height, row.size.height)
    }

    @Test
    fun `entries edited at once are one line, saying how where each changed alike (HI-2, TI-1)`() {
        val groceries = EntryChanges(categoryId = Change.To(BuiltInCategory.GROCERIES.id))
        show {
            groups.append(
                trip,
                listOf(
                    Event.EntryCreated(eventId(), ben.id, hours(1), entry(taxiId, "Taxi")),
                    Event.EntryUpdated(eventId(), anna.id, hours(2), dinnerId, groceries),
                    Event.EntryUpdated(eventId(), anna.id, hours(2), taxiId, groceries),
                ),
                today,
            )
        }
        compose.onNodeWithText("Anna edited 2 entries").assertExists()
        compose.onNodeWithText("Groceries (previous —)", substring = true).assertExists()
        assertEquals(0, count("Anna edited 'Taxi'"))
    }

    @Test
    fun `suggestions wait above the history, and one confirmed counts (RE-4, RE-10)`() {
        show { suggesting() }
        compose.onNodeWithText("Suggestions").assertExists()
        compose
            .onNodeWithText("Declining skips only this one. The series carries on.")
            .assertExists()
        compose.onNodeWithText("Due Tuesday, September 1, 2026").assertExists()
        compose.onNodeWithText("Due Thursday, October 1, 2026").assertExists()
        assertTrue(top("Due Thursday, October 1, 2026") < top("History"))

        compose.onAllNodesWithText("Confirm")[0].performClick()
        compose.waitUntil(5_000) { pending().size == 1 }
        assertEquals(EntryDate.parse("2026-10-01"), pending().single().due)
        // Under the id every installation gives it (format-spec 6).
        val september = EntryDate.parse("2026-09-01")
        assertEquals(EventId.occurrence(cleaning, september), groups.events(trip).last().id)
        compose.waitUntil(5_000) { count("Due Tuesday, September 1, 2026") == 0 }
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun `todos show from the day they are due, below the suggestions, until done (TD-7)`() {
        fun todo(n: Int, description: String, due: String?) =
            Todo(
                TodoId.parse(id(0x40 + n)),
                description,
                due?.let(EntryDate::parse),
                listOf(ben.id),
            )
        val done = todo(4, "Return the keys", "2026-10-01")
        show {
            suggesting()
            groups.append(
                trip,
                listOf(
                        todo(1, "Book the ferry", "2026-10-07"),
                        todo(2, "Buy sunscreen", "2026-10-08"),
                        todo(3, "Pack", null),
                        done,
                        todo(5, "Pay the deposit", "2026-10-02"),
                    )
                    .map { Event.TodoCreated(eventId(), anna.id, at, it) } +
                    Event.TodoCompleted(eventId(), anna.id, at, listOf(done.id)),
                today,
            )
        }
        compose.onNodeWithText("To-dos due").assertExists()
        assertTrue(top("Due Thursday, October 1, 2026") < top("To-dos due"))
        assertTrue(top("Pay the deposit") < top("Book the ferry"))
        assertTrue(top("Book the ferry") < top("History"))
        compose.onAllNodesWithText("Ben").assertCountEquals(2)
        assertEquals(0, todoRows("Buy sunscreen"))
        assertEquals(0, todoRows("Pack"))
        assertEquals(0, todoRows("Return the keys"))

        compose.onAllNodesWithText("Book the ferry")[0].performClick()
        assertEquals(1, todoLists)
    }

    @Test
    fun `a todo due is swiped done or deleted as in the list (TD-5, TD-6)`() {
        val ferry = Todo(TodoId.parse(id(0x41)), "Book the ferry", today, emptyList())
        val keys = Todo(TodoId.parse(id(0x42)), "Return the keys", today, emptyList())
        show {
            groups.actAs(trip, anna.id)
            groups.append(
                trip,
                listOf(ferry, keys).map { Event.TodoCreated(eventId(), anna.id, at, it) },
                today,
            )
        }
        compose.waitUntil(5_000) { count("Book the ferry") > 0 }
        compose.onAllNodesWithText("Book the ferry")[0].performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Done").performClick()
        // Ticked here, it stays to be ticked open again, and is written with the deletion.
        compose.waitForIdle()
        compose.onAllNodesWithText("Book the ferry")[0].assertExists()

        compose.onAllNodesWithText("Return the keys")[0].performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Delete")[1].performClick()
        compose.waitUntil(5_000) { groups.load(trip)!!.todos.first().done }
        compose.waitUntil(5_000) { count("To-do deleted") > 0 }
        assertEquals(0, todoRows("Return the keys"))
        swipeSnackbarAway("To-do deleted")
        assertEquals(0, todoRows("Return the keys"))
        compose.waitUntil(5_000) { groups.load(trip)!!.todos.size == 1 }
        assertEquals(0, todoRows("Return the keys"))
    }

    @Test
    fun `a declined suggestion is gone and its series carries on (RE-4, RE-5)`() {
        show { suggesting() }
        compose.onAllNodesWithText("Decline")[1].performClick()
        compose.waitUntil(5_000) { pending().size == 1 }
        val group = groups.load(trip)!!
        assertEquals(listOf(EntryDate.parse("2026-09-01")), group.occurrences(today).map { it.due })
        assertEquals(false, group.series.getValue(cleaning).deleted)
        val october = EntryDate.parse("2026-10-01")
        assertEquals(EventId.occurrence(cleaning, october), groups.events(trip).last().id)
        val november = EntryDate.parse("2026-11-01")
        assertEquals(2, group.occurrences(november).size)
    }

    @Test
    fun `a suggestion is edited in the editor, which confirms it (RE-4)`() {
        show { suggesting() }
        compose.onAllNodesWithText("Edit")[0].performClick()
        val september = EntryDate.parse("2026-09-01")
        assertEquals(listOf(EntryId.occurrence(cleaning, september)), edited)
    }

    @Test
    fun `every line opens its change in full, whatever it names (HI-10)`() {
        show {
            groups.append(
                trip,
                listOf(
                    Event.EntryCreated(eventId(), anna.id, hours(1), entry(taxiId, "Taxi")),
                    Event.EntryDeleted(eventId(), anna.id, hours(1), taxiId),
                ),
                today,
            )
        }
        compose.onNodeWithText("Ben added 'Dinner'").performClick()
        compose.onNodeWithText("Anna deleted 'Taxi'").performClick()
        compose.onNodeWithText("Anna created the group").performClick()
        val log = groups.events(trip)
        assertEquals(
            listOf(
                log.single { it is Event.EntryCreated && it.entry.id == dinnerId }.id,
                log.single { it is Event.EntryDeleted }.id,
                log.first().id,
            ),
            steps,
        )
    }

    @Test
    fun `a recurring series line opens in full (HI-10, RE-1)`() {
        show { suggesting() }

        compose.onNodeWithText("Ben added 'Cleaning', repeating").performClick()

        assertEquals(listOf(groups.events(trip).single { it is Event.SeriesCreated }.id), steps)
    }

    @Test
    fun `a todo event uses both existing history lines`() {
        val description = "Review every receipt from the summer trip"
        val todo = Todo(TodoId.parse(id(0x50)), description, null, emptyList())
        show {
            groups.append(
                trip,
                listOf(Event.TodoCreated(eventId(), anna.id, hours(1), todo)),
                today,
            )
        }

        compose.onNodeWithText("Anna added a to-do").assertExists()
        compose.onNodeWithText(description).assertExists()
    }

    @Test
    fun `changes from elsewhere stand out until their line is tapped (HI-5)`() {
        show {
            groups.receive(
                trip,
                today,
                1,
                Event.MemberRenamed(eventId(), ben.id, hours(1), ben.id, "Benni"),
                Event.EntryCreated(eventId(), ben.id, hours(1), entry(taxiId, "Taxi")),
            )
        }
        compose.waitUntil(5_000) { unseen() == 2 }
        compose.onNodeWithText("Benni renamed Ben to Benni").performClick()
        compose.waitUntil(5_000) { unseen() == 1 }
        compose.onNodeWithText("Benni added 'Taxi'").performClick()
        assertEquals(2, steps.size)
        compose.waitUntil(5_000) { unseen() == 0 }
        compose.onNodeWithText("Benni added 'Dinner'").assertExists()
    }

    @Test
    fun `a change after one the history leaves out stands out until its line is tapped (HI-5)`() {
        show {
            groups.receive(
                trip,
                today,
                1,
                Event.CategoryDeleted(
                    eventId(),
                    ben.id,
                    hours(1),
                    CategoryId.parse("ff".repeat(16)),
                ),
                Event.EntryCreated(eventId(), ben.id, hours(1), entry(taxiId, "Taxi")),
            )
            groups.seen(trip, groups.events(trip).indexOfFirst { it is Event.CategoryDeleted })
        }
        compose.waitUntil(5_000) { unseen() == 1 }
        compose.onNodeWithText("Ben added 'Taxi'").performClick()
        compose.waitUntil(5_000) { unseen() == 0 }
        assertEquals(emptySet(), runBlocking { groups.unseen(trip).first() })
    }

    @Test
    fun `mark all as read clears every highlight at once (HI-7)`() {
        show {
            groups.receive(
                trip,
                today,
                1,
                Event.MemberRenamed(eventId(), ben.id, hours(1), ben.id, "Benni"),
                Event.EntryCreated(eventId(), ben.id, hours(1), entry(taxiId, "Taxi")),
            )
        }
        compose.waitUntil(5_000) { unseen() == 2 }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Mark all as read").performClick()
        compose.waitUntil(5_000) { unseen() == 0 }
        assertEquals(emptyList(), steps)
    }

    @Test
    fun `a group kept only here offers to share it and shows no sync state (SY-6)`() {
        show()
        compose.onNodeWithText("Only on this device").assertExists()
        compose.onNodeWithText("Set up shared storage").performClick()
        assertEquals(1, shares)
        assertEquals(0, count("Sync now"))
        compose.onNodeWithContentDescription("In sync").assertDoesNotExist()
        compose.onNodeWithContentDescription("Changes waiting").assertDoesNotExist()
    }

    @Test
    fun `a shared group shows what is waiting and syncs on demand (SY-6, SY-9)`() {
        show(::share)
        compose.onNodeWithText("Not synced yet").assertExists()
        compose.onNodeWithText("4 changes waiting").assertExists()
        compose.onNodeWithContentDescription("Changes waiting").assertExists()
        compose.onNodeWithText("Sync now").performClick()
        assertEquals(listOf(trip), synced)
    }

    @Test
    fun `a failed pass shows why next to the last success (SY-6, TR-12, BE-7)`() {
        show {
            share()
            groups.backends.succeeded(trip, hours(1))
            groups.backends.failed(trip, Failure.UNREACHABLE)
        }
        compose.onNodeWithText("Last successful: 1 hour ago").assertExists()
        compose.onNodeWithText("Not reachable").assertExists()
        compose.onNodeWithContentDescription("Sync problem").assertExists()
        compose.onNodeWithText("Sync now").assertExists()
    }

    @Test
    fun `a halted group says sync stopped, leads to why, and offers no manual sync (SY-15)`() {
        show {
            share()
            groups.backends.halted(trip, Upload.Halted(ObjectNumber(3), Halt.GAP))
        }
        compose.onNodeWithContentDescription("Sync stopped").assertExists()
        assertEquals(0, count("Sync now"))
        compose.onNodeWithText("Sync stopped").performClick()
        assertEquals(1, problems)
    }

    @Test
    fun `how sync stands comes before the history, which folds away and back (ui-design 4)`() {
        show()
        val dinner = "Ben added 'Dinner'"
        assertTrue(top("Only on this device") < top("History"))
        compose.onNodeWithContentDescription("Collapse").performClick()
        assertEquals(0, count(dinner))
        compose.onNodeWithText("Only on this device").assertExists()
        compose.onNodeWithContentDescription("Expand").performClick()
        assertEquals(1, count(dinner))
    }
}
