package eu.teele.neugesplitter.ui.overview

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
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
class OverviewScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var issued = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))
    private val dinner = EntryId.parse(id(10))
    private val taxi = EntryId.parse(id(11))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private var opened: EntryId? = null
    private var edited: EntryId? = null
    private var duplicated: EntryId? = null
    private var back = 0
    private var taken = 0

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun entry(
        id: EntryId,
        description: String,
        date: EntryDate,
        amount: Amount,
        payer: Member,
    ) =
        Entry(
            id = id,
            kind = Kind.EXPENSE,
            description = description,
            date = date,
            amount = amount,
            payers = Side.Equal(listOf(payer.id)),
            beneficiaries = Side.Equal(listOf(anna.id, ben.id)),
        )

    /** Anna's group, with her dinner today and Ben's taxi the day before, unless [empty]. */
    private fun show(empty: Boolean = false, deleteRequested: EntryId? = null) {
        groups.create(
            Group.create(trip, "Trip", null, "⛺", null, listOf(anna, ben), at, ::eventId),
            today,
        )
        if (!empty) {
            val meals = entry(dinner, "Dinner", today, cents(3000), anna)
            val ride = entry(taxi, "Taxi", EntryDate.parse("2026-10-06"), cents(1200), ben)
            groups.append(
                trip,
                listOf(meals, ride).map { Event.EntryCreated(eventId(), anna.id, at, it) },
                today,
            )
        }
        val viewModel =
            OverviewViewModel(
                groups,
                trip,
                { id(0x200 + issued++) },
                { today },
                { at },
                CoroutineScope(Dispatchers.Unconfined),
            )
        compose.setContent {
            NeugesplitterTheme {
                OverviewScreen(
                    viewModel,
                    deleteRequested,
                    { taken++ },
                    { back++ },
                    { opened = it },
                    { edited = it },
                    { duplicated = it },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Total spending").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun top(text: String) =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun openMenu(description: String) {
        compose.onNodeWithText(description).performTouchInput { longClick() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Duplicate").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun stored() = groups.load(trip)!!.entries.keys

    @Test
    fun `the header shows the total and where the user stands (BA-7)`() {
        show()
        compose.onNodeWithText("⛺ Trip").assertExists()
        compose.onNodeWithText("42.00").assertExists()
        compose.onNodeWithText("You are owed 9.00").assertExists()
    }

    @Test
    fun `entries are listed newest day first with who paid and the user's share (EN-2, EN-3)`() {
        show()
        compose.onNodeWithText("Wednesday, October 7, 2026").assertExists()
        compose.onNodeWithText("Tuesday, October 6, 2026").assertExists()
        compose.onNodeWithText("Paid by Anna").assertExists()
        compose.onNodeWithText("Paid by Ben").assertExists()
        compose.onNodeWithText("30.00").assertExists()
        compose.onNodeWithText("Your share 15.00").assertExists()
        compose.onNodeWithText("Your share 6.00").assertExists()
        assertTrue(top("Dinner") < top("Taxi"))
    }

    @Test
    fun `a group without entries says so and shows no position (BA-7)`() {
        show(empty = true)
        compose.onNodeWithText("No entries yet").assertExists()
        compose.onNodeWithText("0.00").assertExists()
        assertEquals(
            0,
            compose.onAllNodesWithText("You are owed", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `opening an entry and going back are reported (EN-9)`() {
        show()
        compose.onNodeWithText("Taxi").performClick()
        assertEquals(taxi, opened)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `the long press menu offers edit and duplicate for that entry (EN-9)`() {
        show()
        openMenu("Dinner")
        compose.onNodeWithText("Edit").performClick()
        assertEquals(dinner, edited)
        openMenu("Taxi")
        compose.onNodeWithText("Duplicate").performClick()
        assertEquals(taxi, duplicated)
    }

    @Test
    fun `deleting offers undo and undoing keeps the entry unchanged (EN-9, HI-1)`() {
        show()
        compose.mainClock.autoAdvance = false
        openMenu("Dinner")
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Entry deleted").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Dinner").fetchSemanticsNodes().size)
        // The balance without the entry is read on IO, so the paused clock is stepped along.
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeBy(50)
            compose.onAllNodesWithText("You owe 6.00").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(dinner in stored())

        compose.onNodeWithText("Undo").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Dinner").assertExists()
        compose.onNodeWithText("You are owed 9.00").assertExists()
        assertTrue(dinner in stored())
    }

    @Test
    fun `a deletion nobody undoes is written to the group (EN-9, HI-1)`() {
        show()
        openMenu("Dinner")
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeBy(11_000)
        compose.waitUntil(5_000) { dinner !in stored() }
        assertEquals(setOf(taxi), stored())
        assertTrue(dinner in groups.load(trip)!!.deleted)
    }

    @Test
    fun `an entry the detail screen asked to delete is deleted here (EN-9, HI-1)`() {
        show(deleteRequested = taxi)
        assertEquals(1, taken)
        compose.mainClock.advanceTimeBy(11_000)
        compose.waitUntil(5_000) { taxi !in stored() }
        assertEquals(0, compose.onAllNodesWithText("Taxi").fetchSemanticsNodes().size)
    }
}
