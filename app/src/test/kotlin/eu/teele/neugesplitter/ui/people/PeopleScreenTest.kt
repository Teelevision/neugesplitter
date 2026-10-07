package eu.teele.neugesplitter.ui.people

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.balance.Transfer
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
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class PeopleScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val clara = Member(MemberId.parse(id(3)), "Clara")
    private val trip = GroupId.parse(id(9))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun eventId() = EventId.parse(id(0x100 + events++))

    /** Anna's group, acted in as Anna, after Anna paid [paid] for all three of them. */
    private fun show(paid: Amount?, onRecord: (Transfer) -> Unit = {}) {
        val created =
            Group.create(trip, "Trip", null, "⛺", null, listOf(anna, ben, clara), at, ::eventId)
        groups.create(created, today)
        if (paid != null) {
            val dinner =
                Entry(
                    id = EntryId.parse(id(10)),
                    kind = Kind.EXPENSE,
                    description = "Dinner",
                    date = today,
                    amount = paid,
                    payers = Side.Equal(listOf(anna.id)),
                    beneficiaries = Side.Equal(listOf(anna.id, ben.id, clara.id)),
                )
            groups.append(trip, listOf(Event.EntryCreated(eventId(), anna.id, at, dinner)), today)
        }
        val viewModel = PeopleViewModel(groups, trip) { today }
        compose.setContent { NeugesplitterTheme { PeopleScreen(viewModel, {}, onRecord) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("⛺ Trip").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun top(text: String) =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `members show what they are owed or owe, largest creditor first (BA-1)`() {
        show(paid = cents(3000))
        compose.onNodeWithText("is owed 20.00").assertExists()
        assertEquals(2, compose.onAllNodesWithText("owes 10.00").fetchSemanticsNodes().size)
        assertTrue(top("Anna (you)") < top("Ben"))
        assertTrue(top("Ben") < top("Clara"))
    }

    @Test
    fun `recording a transfer hands it to the entry editor (BA-3, BA-6)`() {
        var recorded: Transfer? = null
        show(paid = cents(3000)) { recorded = it }
        compose.onNodeWithText("Ben pays Anna 10.00").assertExists()
        compose.onNodeWithText("Clara pays Anna 10.00").assertExists()
        compose.onAllNodesWithText("Record")[0].performClick()
        assertEquals(Transfer(ben.id, anna.id, cents(1000)), recorded)
    }

    @Test
    fun `a group with nothing owed needs no transfers (BA-3)`() {
        show(paid = null)
        compose.onNodeWithText("All settled").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Record").fetchSemanticsNodes().size)
    }
}
