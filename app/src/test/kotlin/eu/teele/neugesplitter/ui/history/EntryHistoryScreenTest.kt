package eu.teele.neugesplitter.ui.history

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
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
class EntryHistoryScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var backs = 0
    private val zone = TimeZone.getDefault()

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val clara = Member(MemberId.parse(id(3)), "Clara")
    private val trip = GroupId.parse(id(9))
    private val entryId = EntryId.parse(id(10))

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

    private fun created() = Event.EntryCreated(eventId(), anna.id, at, dinner)

    private fun edited(by: Member, hours: Long, changes: EntryChanges) =
        Event.EntryUpdated(eventId(), by.id, at.plusSeconds(hours * 3600), entryId, changes)

    private fun deleted(by: Member, hours: Long) =
        Event.EntryDeleted(eventId(), by.id, at.plusSeconds(hours * 3600), entryId)

    private fun show(vararg written: Event) {
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna, ben, clara), at, ::eventId),
            today,
        )
        groups.append(trip, written.toList(), today)
        val viewModel = EntryHistoryViewModel(groups, trip, entryId)
        compose.setContent {
            NeugesplitterTheme { EntryHistoryScreen(viewModel, onBack = { backs++ }) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Created by Anna").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun count(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().size

    private fun top(text: String) =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `a new entry shows who created it, when, and the entry as recorded (HI-3)`() {
        show(created())
        // Once as the title and once as the description recorded.
        assertEquals(2, count("Dinner"))
        compose.onNodeWithText("Created by Anna").assertExists()
        compose.onNodeWithText("Oct 7, 2026", substring = true).assertExists()
        compose.onNodeWithText("Expense").assertExists()
        compose.onNodeWithText("Wednesday, October 7, 2026").assertExists()
        compose.onNodeWithText("30.00").assertExists()
        compose.onNodeWithText("Paid by").assertExists()
        compose.onNodeWithText("Anna 30.00").assertExists()
        compose.onNodeWithText("For").assertExists()
        compose.onNodeWithText("Anna 10.00, Ben 10.00, Clara 10.00").assertExists()
        assertEquals(0, backs)
    }

    @Test
    fun `an edit shows only the fields it changed, as old to new (HI-3)`() {
        show(
            created(),
            edited(
                ben,
                1,
                EntryChanges(
                    description = Change.To("Supper"),
                    amount = Change.To(cents(4000)),
                    payers = Change.To(Side.Equal(listOf(ben.id))),
                    note = Change.To("Late"),
                ),
            ),
        )
        compose.onNodeWithText("Edited by Ben").assertExists()
        compose.onNodeWithText("Description: Dinner → Supper").assertExists()
        compose.onNodeWithText("Amount: 30.00 → 40.00").assertExists()
        compose.onNodeWithText("Paid by: Anna 30.00 → Ben 40.00").assertExists()
        compose.onNodeWithText("Note: — → Late").assertExists()
        assertEquals(0, count("Type:", substring = true))
        assertEquals(0, count("Date:", substring = true))
        assertEquals(0, count("For:", substring = true))
        // The title is the entry as it stands now.
        compose.onNodeWithText("Supper").assertExists()
    }

    @Test
    fun `a deletion and a restoration are shown without field lines (HI-3)`() {
        show(created(), deleted(ben, 1), edited(anna, 2, EntryChanges()))
        compose.onNodeWithText("Deleted by Ben").assertExists()
        compose.onNodeWithText("Restored by Anna").assertExists()
        assertEquals(0, count("→", substring = true))
    }

    @Test
    fun `versions are listed newest first (HI-3)`() {
        show(
            created(),
            edited(ben, 1, EntryChanges(description = Change.To("Supper"))),
            deleted(ben, 2),
        )
        compose.onNodeWithText("Deleted by Ben").assertExists()
        assertTrue(top("Deleted by Ben") < top("Edited by Ben"))
        assertTrue(top("Edited by Ben") < top("Created by Anna"))
    }
}
