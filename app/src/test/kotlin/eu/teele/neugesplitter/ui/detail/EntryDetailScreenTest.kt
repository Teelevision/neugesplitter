package eu.teele.neugesplitter.ui.detail

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class EntryDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0

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

    private val calls = mutableListOf<String>()

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun seed(entry: Entry? = null, edited: Boolean = false) {
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna, ben, clara), at, ::eventId),
            today,
        )
        if (entry != null) {
            groups.append(trip, listOf(Event.EntryCreated(eventId(), anna.id, at, entry)), today)
        }
        if (edited) {
            val change = EntryChanges(description = Change.To("Supper"))
            groups.append(
                trip,
                listOf(Event.EntryUpdated(eventId(), ben.id, at, entryId, change)),
                today,
            )
        }
    }

    private fun dinner(kind: Kind = Kind.EXPENSE) =
        Entry(
            id = entryId,
            kind = kind,
            description = "Dinner",
            date = today,
            amount = cents(10000),
            payers = Side.Equal(listOf(anna.id)),
            beneficiaries = Side.Equal(listOf(anna.id, ben.id, clara.id)),
        )

    private fun show() {
        val viewModel = EntryDetailViewModel(groups, trip, entryId)
        compose.setContent {
            NeugesplitterTheme {
                EntryDetailScreen(
                    viewModel,
                    onBack = { calls += "back" },
                    onEdit = { calls += "edit" },
                    onDuplicate = { calls += "duplicate" },
                    onHistory = { calls += "history" },
                    onDelete = { calls += "delete" },
                )
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `an expense shows who paid and who benefited with each part (EN-4, EN-5, HI-4)`() {
        seed(dinner())
        show()
        waitForText("Dinner")
        compose.onNodeWithText("Wednesday, October 7, 2026").assertExists()
        compose.onNodeWithText("Paid by").assertExists()
        compose.onNodeWithText("For").assertExists()
        // Anna appears on both sides, Ben and Clara once.
        assertEquals(2, compose.onAllNodesWithText("Anna").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Ben").fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithText("100.00").fetchSemanticsNodes().size)
        assertEquals(3, compose.onAllNodesWithText("33.33").fetchSemanticsNodes().size)
        compose.onNodeWithText("Created by Anna").assertExists()
    }

    @Test
    fun `income is signed and its sides are named for receiving (EN-8)`() {
        seed(dinner(Kind.INCOME))
        show()
        waitForText("Dinner")
        compose.onNodeWithText("+100.00").assertExists()
        compose.onNodeWithText("Received by").assertExists()
    }

    @Test
    fun `an edited entry says so and keeps its author (HI-4)`() {
        seed(dinner(), edited = true)
        show()
        waitForText("Supper")
        compose.onNodeWithText("Created by Anna · edited").assertExists()
    }

    @Test
    fun `edit, delete and duplicate report what was chosen (EN-9)`() {
        seed(dinner())
        show()
        waitForText("Dinner")
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Duplicate").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("edit", "delete", "duplicate", "back"), calls)
    }

    @Test
    fun `history is offered in the overflow menu (HI-3)`() {
        seed(dinner())
        show()
        waitForText("Dinner")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("History").performClick()
        assertEquals(listOf("history"), calls)
    }

    @Test
    fun `an entry that no longer exists takes the user back (EN-9)`() {
        seed()
        show()
        // The state arrives from IO; nothing in the tree changes, so the looper is drained here.
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            "back" in calls
        }
        assertEquals(0, compose.onAllNodesWithText("Dinner").fetchSemanticsNodes().size)
    }
}
