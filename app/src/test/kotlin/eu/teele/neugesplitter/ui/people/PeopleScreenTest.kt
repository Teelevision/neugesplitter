package eu.teele.neugesplitter.ui.people

import android.os.Looper
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
    private fun show(paid: Amount?, onRecord: (Transfer) -> Unit = {}, shared: Boolean = false) {
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
        if (shared) {
            groups.backends.connect(
                trip,
                BackendSettings("https://s3.example", "splits", "", "auto", false, "AK"),
            )
        }
        val viewModel = PeopleViewModel(groups, trip, { id(0x100 + events++) }, { today }, { at })
        compose.setContent { NeugesplitterTheme { PeopleScreen(viewModel, {}, onRecord) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("⛺ Trip").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits for something the view model wrote on IO. */
    private fun await(condition: () -> Boolean) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }

    private fun editName(text: String) =
        compose.onNode(hasSetTextAction()).performTextReplacement(text)

    private fun longPress(name: String) =
        compose.onNodeWithText(name).performTouchInput { longClick() }

    private fun top(text: String) =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `a shared group's title follows how its sync stands, a local one's stands alone (SY-6)`() {
        show(paid = null, shared = true)
        val cloud = compose.onNodeWithContentDescription("Changes waiting")
        assertTrue(
            cloud.fetchSemanticsNode().boundsInRoot.left >=
                compose.onNodeWithText("⛺ Trip").fetchSemanticsNode().boundsInRoot.right
        )
    }

    @Test
    fun `members show what they are owed or owe, in the order they were added (BA-1)`() {
        show(paid = cents(3000))
        compose.onNodeWithContentDescription("In sync").assertDoesNotExist()
        compose.onNodeWithContentDescription("Changes waiting").assertDoesNotExist()
        compose.onNodeWithText("is owed 20.00").assertExists()
        assertEquals(2, compose.onAllNodesWithText("owes 10.00").fetchSemanticsNodes().size)
        assertTrue(top("Anna (you)") < top("Ben"))
        assertTrue(top("Ben") < top("Clara"))
    }

    @Test
    fun `recording a transfer hands it to the entry editor (BA-3, BA-6)`() {
        var recorded: Transfer? = null
        show(paid = cents(3000), onRecord = { recorded = it })
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

    @Test
    fun `a member can be added and starts out settled (GR-5)`() {
        show(paid = null)
        compose.onNodeWithText("Add member").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(" Dora ")
        compose.onNodeWithContentDescription("Save").performClick()
        await { groups.load(trip)!!.members.values.any { it.name == "Dora" } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Dora").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(4, compose.onAllNodesWithText("settled").fetchSemanticsNodes().size)
    }

    @Test
    fun `a long press renames a member in place, and the same name writes nothing (ID-2)`() {
        show(paid = null)
        longPress("Ben")
        editName("Benjamin")
        compose.onNode(hasSetTextAction()).performImeAction()
        await { groups.load(trip)!!.members.getValue(ben.id).name == "Benjamin" }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Benjamin").fetchSemanticsNodes().isNotEmpty()
        }

        val written = events
        longPress("Benjamin")
        editName("  Benjamin ")
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(written, events)
    }

    @Test
    fun `a blank name cannot be saved (ID-2)`() {
        show(paid = null)
        longPress("Ben")
        editName("   ")
        compose.onNodeWithContentDescription("Save").assertIsNotEnabled()
        val written = events
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(written, events)
        assertEquals("Ben", groups.load(trip)!!.members.getValue(ben.id).name)
    }

    @Test
    fun `removing a member asks first, keeps their balance and offers them no more (GR-6, GR-7, BE-13)`() {
        show(paid = cents(3000))
        val settled = groups.load(trip)!!.settlement(today)
        compose.onNodeWithText("Clara").performClick()
        compose.onNodeWithText("Remove from group").performClick()
        compose.onNodeWithText("Remove Clara?").assertExists()
        compose
            .onNodeWithText(
                "Clara will no longer be offered for new entries. Their past entries and their " +
                    "balance stay exactly as they are.",
                substring = true,
            )
            .assertExists()
        compose
            .onNodeWithText("whoever has the storage credentials", substring = true)
            .assertExists()
        assertTrue(clara.id !in groups.load(trip)!!.removed)

        compose.onNodeWithText("Remove").performClick()
        await { clara.id in groups.load(trip)!!.removed }
        assertEquals(settled, groups.load(trip)!!.settlement(today))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Removed").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, compose.onAllNodesWithText("owes 10.00").fetchSemanticsNodes().size)

        compose.onNodeWithText("Clara").performClick()
        compose.onNodeWithText("Rename").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Remove from group").fetchSemanticsNodes().size)
    }

    @Test
    fun `the member this device acts as cannot be removed (GR-6)`() {
        show(paid = null)
        compose.onNodeWithText("Anna (you)").performClick()
        compose.onNodeWithText("Rename").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Remove from group").fetchSemanticsNodes().size)
    }
}
