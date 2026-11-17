package eu.teele.neugesplitter.ui.grouplist

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import eu.teele.neugesplitter.ui.receive
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
    private var issued = 0

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

    private fun show(
        onNewGroup: () -> Unit = {},
        onJoin: () -> Unit = {},
        onOpen: (GroupId) -> Unit = {},
        onSettings: () -> Unit = {},
        onExport: (List<GroupId>) -> Unit = {},
        onImport: () -> Unit = {},
    ) {
        val viewModel = GroupListViewModel(groups, { id(0x500 + issued++) }, { today }, { at })
        compose.setContent {
            NeugesplitterTheme {
                GroupListScreen(
                    viewModel,
                    onNewGroup,
                    onJoin,
                    onOpen,
                    onSettings,
                    onExport,
                    onImport,
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
    fun `without groups the user is offered a new one, joining one or importing one (GR-1, BE-5, DA-3)`() {
        var created = 0
        var joined = 0
        var imported = 0
        show(onNewGroup = { created++ }, onJoin = { joined++ }, onImport = { imported++ })
        waitForText("A group stays on this device or is shared.")
        compose.onNodeWithText("New group").performClick()
        compose.onNodeWithText("Join a shared group").performClick()
        compose.onNodeWithText("Import from file").performClick()
        assertEquals(1, created)
        assertEquals(1, joined)
        assertEquals(1, imported)
    }

    @Test
    fun `the add button offers a new group, joining one or importing one (GR-1, BE-5, DA-3)`() {
        seed(trip, "Trip")
        var created = 0
        var joined = 0
        var imported = 0
        show(onNewGroup = { created++ }, onJoin = { joined++ }, onImport = { imported++ })
        waitForText("Trip")
        compose.onNodeWithContentDescription("New group").performClick()
        compose.onNodeWithText("Join a shared group").performClick()
        assertEquals(1, joined)
        compose.onNodeWithText("Join a shared group").assertDoesNotExist()

        compose.onNodeWithContentDescription("New group").performClick()
        compose.onNodeWithText("Import from file").performClick()
        assertEquals(1, imported)

        compose.onNodeWithContentDescription("New group").performClick()
        compose.onNodeWithText("New group").performClick()
        assertEquals(1, created)
    }

    @Test
    fun `a group with unseen changes carries a dot, a shared one how its sync stands (HI-5, SY-6, SY-15)`() {
        seed(trip, "Trip")
        seed(flat, "Flat")
        val renamed = Event.MemberRenamed(eventId(), ben.id, at, ben.id, "Benni")
        groups.receive(trip, today, 1, renamed)
        groups.receive(flat, today, 1, renamed.copy(id = eventId()))
        groups.backends.connect(
            flat,
            BackendSettings("https://s3.example", "splits", "", "auto", false, "AK"),
        )
        groups.backends.halted(flat, Upload.Halted(ObjectNumber(2), Halt.GAP))
        show()
        waitForText("Trip")
        fun count(label: String) =
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size
        assertEquals(2, count("New"))
        assertEquals(1, count("Sync stopped"))
        for (other in listOf("In sync", "Changes waiting", "Sync problem")) {
            assertEquals(0, count(other))
        }

        groups.seenAll(trip)
        compose.waitUntil(5_000) { count("New") == 1 }
        groups.backends.succeeded(flat, at)
        compose.waitUntil(5_000) { count("In sync") == 1 }
    }

    @Test
    fun `the menu opens the app settings (UX-3)`() {
        var opened = 0
        show(onSettings = { opened++ })
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `exporting starts with every group selected and hands over those left (DA-3)`() {
        seed(trip, "Trip")
        seed(flat, "Flat")
        var exported: List<GroupId>? = null
        show(onExport = { exported = it })
        waitForText("Flat")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("2 selected").assertExists()
        compose.onNodeWithContentDescription("Delete").assertDoesNotExist()

        compose.onNodeWithText("Flat").performClick()
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText("Export").performClick()

        assertEquals(listOf(trip), exported)
        compose.onNodeWithText("Neugesplitter").assertExists()
        assertNotNull(groups.load(flat))
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
        compose.onNodeWithText("Trip").assertIsSelected()
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

    @Test
    fun `a selected group is duplicated with its history but not its backend, under a name asked for`() {
        seed(trip, "Trip", paid = cents(3000))
        seed(flat, "Flat")
        groups.backends.connect(
            trip,
            BackendSettings("https://s3.example", "splits", "", "auto", false, "AK"),
        )
        show()
        waitForText("Trip")
        compose.onNodeWithText("Trip").performTouchInput { longClick() }
        compose.onNodeWithText("Flat").performClick()
        compose.onNodeWithContentDescription("Duplicate").assertDoesNotExist()
        compose.onNodeWithText("Flat").performClick()

        // The progress indicator animates forever, so the clock is advanced by hand.
        compose.mainClock.autoAdvance = false
        fun settle() = compose.mainClock.advanceTimeBy(1_000)
        fun until(condition: () -> Boolean) =
            compose.waitUntil(5_000) {
                settle()
                condition()
            }
        settle()
        compose.onNodeWithContentDescription("Duplicate").performClick()
        settle()
        compose.onNodeWithText("Trip (copy)").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        settle()
        compose.onNodeWithText("Trip (copy)").assertDoesNotExist()
        compose.onNodeWithText("1 selected").assertExists()

        val copy = GroupId.parse(id(0x501))
        compose.onNodeWithContentDescription("Duplicate").performClick()
        settle()
        compose.onNodeWithText("Duplicate").performClick()
        until {
            compose.onAllNodesWithText("Copying the history…").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Trip (copy)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Neugesplitter").assertExists()

        val copied = assertNotNull(groups.load(copy))
        assertEquals("Trip (copy)", copied.name)
        assertEquals(groups.load(trip)!!.entries.keys, copied.entries.keys)
        assertEquals(anna.id, groups.actingAs(copy))
        assertNull(groups.backends.settings(copy))
        assertEquals(2, compose.onAllNodesWithText("You are owed 15.00").fetchSemanticsNodes().size)

        compose.onNodeWithText("Trip").performTouchInput { longClick() }
        settle()
        compose.onNodeWithContentDescription("Duplicate").performClick()
        settle()
        compose.onNodeWithText("Trip (copy 2)").performTextReplacement("Experiment")
        settle()
        compose.onNodeWithText("Duplicate").performClick()
        until { compose.onAllNodesWithText("Experiment").fetchSemanticsNodes().isNotEmpty() }
    }
}
