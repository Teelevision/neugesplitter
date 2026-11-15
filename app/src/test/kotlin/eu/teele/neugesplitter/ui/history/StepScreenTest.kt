package eu.teele.neugesplitter.ui.history

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.category.Category
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
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
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class StepScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private val later = at.plusSeconds(3600)
    private var events = 0
    private var backs = 0
    private val entries = mutableListOf<EntryId>()
    private val histories = mutableListOf<EntryId>()
    private val opened = mutableListOf<SeriesId>()
    private val zone = TimeZone.getDefault()

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val clara = Member(MemberId.parse(id(3)), "Clara")
    private val trip = GroupId.parse(id(9))
    private val entryId = EntryId.parse(id(10))
    private val rent = SeriesId.parse(id(20))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = TimeZone.setDefault(zone)

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private val dinner =
        Entry(
            id = entryId,
            kind = Kind.EXPENSE,
            description = "Dinner",
            date = today,
            amount = cents(3000),
            payers = Side.Equal(listOf(anna.id)),
            beneficiaries = Side.Equal(listOf(anna.id, ben.id, clara.id)),
        )

    private val monthly = Segment(Interval.MonthlyOn(setOf(1)), Mode.AUTOMATIC, Template.of(dinner))

    private fun created() = Event.EntryCreated(eventId(), anna.id, at, dinner)

    /** [written] in group "Trip", then the step of [open], or of its first event without one. */
    private fun show(
        vararg written: Event,
        open: EventId? = null,
        fromEntry: Boolean = false,
        fromSeries: Boolean = false,
    ) {
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna, ben, clara), at, ::eventId),
            today,
        )
        if (written.isNotEmpty()) groups.append(trip, written.toList(), today)
        val viewModel = StepViewModel(groups, trip, open ?: groups.events(trip).first().id)
        compose.setContent {
            NeugesplitterTheme {
                StepScreen(
                    viewModel,
                    onBack = { backs++ },
                    onEntry = if (fromEntry) null else ({ entries += it }),
                    onEntryHistory = if (fromEntry) null else ({ histories += it }),
                    onSeries = if (fromSeries) null else ({ opened += it }),
                )
            }
        }
        compose.waitUntil(5_000) {
            backs > 0 ||
                compose
                    .onAllNodesWithText("Wednesday, October 7, 2026, ", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }

    private fun count(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().size

    private fun top(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().first().boundsInRoot.top

    @Test
    fun `an entry as added is shown in full, with ways to it and its history (HI-10)`() {
        val made = created()
        show(made, open = made.id)
        compose.onNodeWithText("Anna added 'Dinner'").assertExists()
        compose.onNodeWithText("Wednesday, October 7, 2026, 12:00", substring = true).assertExists()
        compose.onNodeWithText("Wednesday, October 7, 2026").assertExists()
        compose.onNodeWithText("Dinner").assertExists()
        compose.onNodeWithText("Expense").assertExists()
        compose.onNodeWithText("30.00").assertExists()
        compose.onNodeWithText("Paid by").assertExists()
        compose.onNodeWithText("Anna 30.00").assertExists()
        compose.onNodeWithText("For").assertExists()
        compose.onNodeWithText("Anna 10.00, Ben 10.00, Clara 10.00").assertExists()
        assertEquals(0, count("Open its series"))

        compose.onNodeWithText("Open entry").performClick()
        compose.onNodeWithText("Entry history").performClick()
        assertEquals(listOf(entryId), entries)
        assertEquals(listOf(entryId), histories)
        assertEquals(0, backs)
    }

    @Test
    fun `an edit shows each field it changed on a line of its own (HI-3, HI-10)`() {
        val edit =
            Event.EntryUpdated(
                eventId(),
                ben.id,
                later,
                entryId,
                EntryChanges(
                    description = Change.To("Supper"),
                    amount = Change.To(cents(4000)),
                    payers = Change.To(Side.Equal(listOf(ben.id))),
                    note = Change.To("Late"),
                ),
            )
        show(created(), edit, open = edit.id)
        compose.onNodeWithText("Ben edited 'Supper'").assertExists()
        compose.onNodeWithText("Description changed to Supper (previous Dinner)").assertExists()
        compose.onNodeWithText("Amount changed to 40.00 (previous 30.00)").assertExists()
        compose.onNodeWithText("Paid by changed to Ben 40.00 (previous Anna 30.00)").assertExists()
        compose.onNodeWithText("Note changed to Late (previous —)").assertExists()
        assertEquals(0, count("Type changed", substring = true))
        assertEquals(0, count("Date changed", substring = true))
        assertEquals(0, count("For changed", substring = true))
        assertEquals(0, count("Paid by"))
    }

    @Test
    fun `a deleted entry is shown as it was, and leads only to its history (HI-10)`() {
        val gone = Event.EntryDeleted(eventId(), ben.id, later, entryId)
        show(created(), gone, open = gone.id)
        compose.onNodeWithText("Ben deleted 'Dinner'").assertExists()
        compose.onNodeWithText("Anna 30.00").assertExists()
        assertEquals(0, count("Open entry"))
        compose.onNodeWithText("Entry history").assertExists()
    }

    @Test
    fun `opened from its entry's history, it leads nowhere else (HI-10)`() {
        val made = created()
        show(made, open = made.id, fromEntry = true)
        compose.onNodeWithText("Anna added 'Dinner'").assertExists()
        assertEquals(0, count("Open entry"))
        assertEquals(0, count("Entry history"))
    }

    @Test
    fun `the group as created is shown with its settings (HI-10)`() {
        show()
        compose.onNodeWithText("Anna created the group").assertExists()
        compose.onNodeWithText("Name").assertExists()
        compose.onNodeWithText("Trip").assertExists()
        compose.onNodeWithText("Decimal places").assertExists()
        compose.onNodeWithText("2").assertExists()
        assertEquals(0, count("Description"))
        assertEquals(0, count("Emoji"))
    }

    @Test
    fun `settings changed at once are shown one by one, from any of their events (HI-2, HI-10)`() {
        val second =
            Event.GroupUpdated(
                eventId(),
                anna.id,
                later,
                GroupChanges(description = Change.To("Summer")),
            )
        show(
            Event.GroupUpdated(eventId(), anna.id, later, GroupChanges(emoji = Change.To("⛰"))),
            second,
            open = second.id,
        )
        compose.onNodeWithText("Anna changed the group settings").assertExists()
        compose.onNodeWithText("Description changed to Summer (previous —)").assertExists()
        compose.onNodeWithText("Emoji changed to ⛰ (previous —)").assertExists()
    }

    @Test
    fun `categories added at once are shown one by one (HI-2, HI-10)`() {
        val snacks = Category(CategoryId.parse(id(30)), name = "Snacks", emoji = "🍿")
        val ferry = Category(CategoryId.parse(id(31)), name = "Ferry", emoji = "⛴")
        val first = Event.CategoryCreated(eventId(), anna.id, later, snacks)
        show(first, Event.CategoryCreated(eventId(), anna.id, later, ferry), open = first.id)
        compose.onNodeWithText("Anna added 2 categories").assertExists()
        compose.onNodeWithText("🍿 Snacks").assertExists()
        compose.onNodeWithText("⛴ Ferry").assertExists()
    }

    @Test
    fun `an entry made recurring shows it as it was, then the series (HI-10, RE-1)`() {
        val gone = Event.EntryDeleted(eventId(), anna.id, later, entryId)
        show(
            created(),
            gone,
            Event.SeriesCreated(eventId(), anna.id, later, rent, today, monthly),
            open = gone.id,
        )
        compose.onNodeWithText("Anna made 'Dinner' recurring").assertExists()
        compose.onNodeWithText("Anna deleted 'Dinner'").assertExists()
        compose.onNodeWithText("Anna added 'Dinner', repeating").assertExists()
        compose.onNodeWithText("Every month on the 1st, automatic").assertExists()
        assertEquals(2, count("Anna 30.00"))
        assertTrue(top("Anna deleted 'Dinner'") < top("Anna added 'Dinner', repeating"))
        assertEquals(0, count("Open entry"))

        compose.onNodeWithText("Open its series").performClick()
        assertEquals(listOf(rent), opened)
    }

    @Test
    fun `several changes to a series at once are each shown, oldest first (HI-10, RE-6)`() {
        val dearer = monthly.copy(template = monthly.template.copy(amount = cents(4000)))
        val amended =
            Event.SeriesAmended(
                eventId(),
                anna.id,
                later,
                rent,
                EntryDate.parse("2026-11-01"),
                dearer,
            )
        show(
            Event.SeriesCreated(eventId(), anna.id, at, rent, today, monthly),
            amended,
            Event.SeriesStopped(eventId(), anna.id, later, rent, EntryDate.parse("2027-01-01")),
            open = amended.id,
            fromSeries = true,
        )
        // As the headline, and over its own part.
        assertEquals(2, count("Anna changed the series 'Dinner'"))
        compose.onNodeWithText("From Nov 1, 2026").assertExists()
        compose.onNodeWithText("Amount changed to 40.00 (previous 30.00)").assertExists()
        compose.onNodeWithText("Anna stopped 'Dinner'").assertExists()
        compose.onNodeWithText("From Jan 1, 2027, for good").assertExists()
        assertTrue(top("Amount changed to 40.00 (previous 30.00)") < top("Anna stopped 'Dinner'"))
        assertEquals(0, count("Open its series"))
    }

    @Test
    fun `an edit names the category it set, and the one before (HI-3, HI-10)`() {
        val groceries = BuiltInCategory.GROCERIES.id
        val edit =
            Event.EntryUpdated(
                eventId(),
                ben.id,
                later,
                entryId,
                EntryChanges(categoryId = Change.To(groceries)),
            )
        show(created(), edit, open = edit.id)
        assertEquals(1, count("Category changed to ", substring = true))
        assertEquals(1, count(" Groceries (previous —)", substring = true))
    }

    @Test
    fun `a series given a category says which (HI-10, RE-6)`() {
        val sorted =
            monthly.copy(
                template = monthly.template.copy(categoryId = BuiltInCategory.GROCERIES.id)
            )
        val amended =
            Event.SeriesAmended(
                eventId(),
                anna.id,
                later,
                rent,
                EntryDate.parse("2026-11-01"),
                sorted,
            )
        show(
            Event.SeriesCreated(eventId(), anna.id, at, rent, today, monthly),
            amended,
            open = amended.id,
        )
        compose.onNodeWithText("From Nov 1, 2026").assertExists()
        assertEquals(1, count(" Groceries (previous —)", substring = true))
    }

    @Test
    fun `entries edited at once are each shown with what changed (HI-2, HI-10, TI-1)`() {
        val taxi = dinner.copy(id = EntryId.parse(id(11)), description = "Taxi")
        val first =
            Event.EntryUpdated(
                eventId(),
                anna.id,
                later,
                entryId,
                EntryChanges(note = Change.To("Tidied")),
            )
        show(
            created(),
            Event.EntryCreated(eventId(), anna.id, at, taxi),
            first,
            Event.EntryUpdated(
                eventId(),
                anna.id,
                later,
                taxi.id,
                EntryChanges(note = Change.To("Tidied")),
            ),
            open = first.id,
        )
        compose.onNodeWithText("Anna edited 2 entries").assertExists()
        compose.onNodeWithText("Anna edited 'Dinner'").assertExists()
        compose.onNodeWithText("Anna edited 'Taxi'").assertExists()
        assertEquals(2, count("Note changed to Tidied (previous —)"))
        assertEquals(0, count("Open entry"))
    }

    @Test
    fun `todos completed at once are all listed (HI-10, TD-5)`() {
        val ferry = Todo(TodoId.parse(id(0x40)), "Book the ferry", null, emptyList())
        val pack = Todo(TodoId.parse(id(0x41)), "Pack", null, emptyList())
        val done = Event.TodoCompleted(eventId(), ben.id, later, listOf(ferry.id, pack.id))
        show(
            Event.TodoCreated(eventId(), anna.id, at, ferry),
            Event.TodoCreated(eventId(), anna.id, at, pack),
            done,
            open = done.id,
        )
        compose.onNodeWithText("Ben completed 2 to-dos").assertExists()
        compose.onNodeWithText("Book the ferry").assertExists()
        compose.onNodeWithText("Pack").assertExists()
    }

    @Test
    fun `an event not in the log closes it`() {
        show(open = EventId.parse(id(0x999)))
        compose.waitUntil(5_000) { backs > 0 }
    }
}
