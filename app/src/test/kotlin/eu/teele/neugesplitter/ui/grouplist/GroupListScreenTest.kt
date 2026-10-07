package eu.teele.neugesplitter.ui.grouplist

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class GroupListScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))
    private val flat = GroupId.parse(id(8))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    /** A group Anna created with Ben, with [paid] spent by Anna on both of them if not null. */
    private fun seed(group: GroupId, name: String, paid: Amount? = null) {
        groups.create(
            Group.create(group, name, null, "⛺", null, listOf(anna, ben), at, ::eventId),
            today,
        )
        if (paid != null) {
            val dinner =
                Entry(
                    id = EntryId.parse(id(10 + events)),
                    kind = Kind.EXPENSE,
                    description = "Dinner",
                    date = today,
                    amount = paid,
                    payers = Side.Equal(listOf(anna.id)),
                    beneficiaries = Side.Equal(listOf(anna.id, ben.id)),
                )
            groups.append(group, listOf(Event.EntryCreated(eventId(), anna.id, at, dinner)), today)
        }
    }

    private fun show(onNewGroup: () -> Unit = {}, onOpen: (GroupId) -> Unit = {}) {
        val viewModel = GroupListViewModel(groups) { today }
        compose.setContent { NeugesplitterTheme { GroupListScreen(viewModel, onNewGroup, onOpen) } }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `every group is listed with where the user stands in it (GR-2, BA-7)`() {
        seed(trip, "Trip", paid = cents(3000))
        seed(flat, "Flat")
        show()
        waitForText("Trip")
        compose.onNodeWithText("Flat").assertExists()
        compose.onNodeWithText("You are owed 15.00").assertExists()
        compose.onNodeWithText("No entries yet").assertExists()
    }

    @Test
    fun `without groups the user is offered a new one (GR-1)`() {
        var created = 0
        show(onNewGroup = { created++ })
        waitForText("A group stays on this device or is shared.")
        compose.onNodeWithText("New group").performClick()
        assertEquals(1, created)
    }

    @Test
    fun `opening a group hands over its id (GR-1)`() {
        seed(trip, "Trip")
        seed(flat, "Flat")
        var opened: GroupId? = null
        show(onOpen = { opened = it })
        waitForText("Flat")
        compose.onNodeWithText("Flat").performClick()
        assertEquals(flat, opened)
    }

    @Test
    fun `a long press selects a group and clearing the selection leaves it alone (GR-11)`() {
        seed(trip, "Trip")
        show()
        waitForText("Trip")
        compose.onNodeWithText("Trip").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.onNodeWithText("Neugesplitter").assertExists()
        assertNotNull(groups.load(trip))
    }

    @Test
    fun `deleting a selected group asks first and then removes it from the device (GR-11)`() {
        seed(trip, "Trip")
        seed(flat, "Flat")
        show()
        waitForText("Trip")
        compose.onNodeWithText("Trip").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete group?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertNotNull(groups.load(trip))

        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { groups.load(trip) == null }
        assertNull(groups.load(trip))
        assertNotNull(groups.load(flat))
        compose.onNodeWithText("Flat").assertExists()
    }
}
