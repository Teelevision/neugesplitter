package eu.teele.neugesplitter.ui.settings

import android.os.Looper
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.theme.GROUP_COLOURS
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class GroupSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var ids = 0
    private var deleted = false

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val clara = Member(MemberId.parse(id(3)), "Clara")
    private val trip = GroupId.parse(id(9))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + ids++))

    /** Anna's group "Trip", with no description, emoji or colour, acted in as Anna. */
    private fun show() {
        val created =
            Group.create(trip, "Trip", null, null, null, listOf(anna, ben, clara), at, ::eventId)
        groups.create(created, today)
        val viewModel =
            GroupSettingsViewModel(groups, trip, { id(0x100 + ids++) }, { today }, { at })
        compose.setContent {
            NeugesplitterTheme {
                GroupSettingsScreen(viewModel, onBack = {}, onDeleted = { deleted = true })
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Trip").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits for something the view model wrote on IO. */
    private fun await(condition: () -> Boolean) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }

    private fun awaitText(text: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitGone(text: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }

    private fun open(row: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(row))
        compose.onNodeWithText(row).performClick()
    }

    private fun typeInto(text: String) =
        compose.onNode(hasSetTextAction()).performTextReplacement(text)

    /** Opens [row]'s inline editor, replaces its text and saves with the check button. */
    private fun saveText(row: String, text: String) {
        open(row)
        typeInto(text)
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun stored() = groups.load(trip)!!

    private fun count() = groups.events(trip).size

    private fun lastChange() = (groups.events(trip).last() as Event.GroupUpdated).group

    @Test
    fun `renaming stores only the name, trimmed, and the same name writes nothing (GR-2)`() {
        show()
        saveText("Name", " Holiday ")
        await { stored().name == "Holiday" }
        assertEquals(GroupChanges(name = Change.To("Holiday")), lastChange())
        awaitText("Holiday")

        val written = count()
        saveText("Name", "  Holiday ")
        assertEquals(written, count())
    }

    @Test
    fun `a blank name cannot be saved (GR-2)`() {
        show()
        open("Name")
        typeInto("   ")
        compose.onNodeWithContentDescription("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
        assertEquals("Trip", stored().name)
    }

    @Test
    fun `a description can be set and cleared, and blank means none (GR-2)`() {
        show()
        compose.onNodeWithText("None").assertExists()
        saveText("Description", " Lake and mountains ")
        await { stored().description == "Lake and mountains" }
        assertEquals(GroupChanges(description = Change.To("Lake and mountains")), lastChange())
        awaitText("Lake and mountains")

        saveText("Description", "   ")
        await { stored().description == null }
        assertEquals(GroupChanges(description = Change.To(null)), lastChange())
        awaitText("None")
    }

    @Test
    fun `the same description writes nothing (GR-2)`() {
        show()
        val written = count()
        saveText("Description", "")
        assertEquals(written, count())
    }

    @Test
    fun `choosing an emoji stores it and choosing it again clears it (GR-2)`() {
        show()
        compose.onNodeWithText("🏠").performClick()
        await { stored().emoji == "🏠" }
        assertEquals(GroupChanges(emoji = Change.To("🏠")), lastChange())
        awaitText("🏠 Trip")

        compose.onNodeWithText("🏠").performClick()
        await { stored().emoji == null }
        assertEquals(GroupChanges(emoji = Change.To(null)), lastChange())
    }

    @Test
    fun `choosing a colour stores it, and the one the group has writes nothing (GR-2)`() {
        show()
        compose.onNodeWithContentDescription("Blue").performClick()
        await { stored().colour == GROUP_COLOURS[2] }
        assertEquals(GroupChanges(colour = Change.To(GROUP_COLOURS[2])), lastChange())
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("Blue").assertIsSelected() }
                .isSuccess
        }

        val written = count()
        compose.onNodeWithContentDescription("Blue").performClick()
        compose.waitForIdle()
        assertEquals(written, count())
    }

    @Test
    fun `default participants are everyone until some are unchecked, and all checked is everyone again (SP-8)`() {
        show()
        compose.onNodeWithText("Everyone").assertExists()
        open("Default participants")
        compose.onNodeWithText("Clara").performClick()
        compose.onNodeWithText("Save").performClick()
        await { stored().defaultParticipants == listOf(anna.id, ben.id) }
        assertEquals(
            GroupChanges(defaultParticipants = Change.To(listOf(anna.id, ben.id))),
            lastChange(),
        )
        awaitText("Anna, Ben")

        open("Default participants")
        compose.onNodeWithText("Clara").performClick()
        compose.onNodeWithText("Save").performClick()
        await { stored().defaultParticipants == null }
        assertEquals(GroupChanges(defaultParticipants = Change.To(null)), lastChange())
        awaitText("Everyone")
    }

    @Test
    fun `at least one participant must stay checked, and unchanged ones write nothing (SP-8)`() {
        show()
        val written = count()
        open("Default participants")
        compose.onNodeWithText("Save").performClick()
        awaitGone("Save")
        assertEquals(written, count())

        open("Default participants")
        for (member in listOf("Anna", "Ben", "Clara")) compose.onNodeWithText(member).performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        awaitGone("Cancel")
        assertEquals(written, count())
        assertNull(stored().defaultParticipants)
    }

    @Test
    fun `the group is kept on this device only, and says what that risks (DA-1)`() {
        show()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Only on this device"))
        compose.onNodeWithText("Only on this device").assertExists()
        compose
            .onNodeWithText("Quick to set up. This group is lost if the device is lost.")
            .assertExists()
    }

    @Test
    fun `deleting asks first, then removes the group from this device (GR-11)`() {
        show()
        open("Delete group from this device")
        compose.onNodeWithText("Delete group?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        awaitGone("Delete group?")
        assertTrue(groups.load(trip) != null)
        assertTrue(!deleted)

        open("Delete group from this device")
        compose.onNodeWithText("Delete").performClick()
        await { deleted }
        assertNull(groups.load(trip))
    }
}
