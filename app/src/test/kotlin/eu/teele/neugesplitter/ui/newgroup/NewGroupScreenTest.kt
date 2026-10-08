package eu.teele.neugesplitter.ui.newgroup

import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.theme.GROUP_COLOURS
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class NewGroupScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var issued = 0
    private var closed = 0
    private var created: GroupId? = null
    private var shared: Boolean? = null

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun show(identifier: () -> String = { "%032x".format(++issued) }) {
        val viewModel = NewGroupViewModel(groups, identifier, { today }, { at })
        compose.setContent {
            NeugesplitterTheme {
                NewGroupScreen(
                    viewModel,
                    { closed++ },
                    { id, choice ->
                        created = id
                        shared = choice
                    },
                )
            }
        }
    }

    private fun type(label: String, text: String) {
        compose.onAllNodesWithText(label)[0].performTextInput(text)
    }

    private fun next() {
        compose.onNodeWithText("Next").performClick()
    }

    // The view model resumes on the main looper after storing on IO, which only runs when pumped.
    private fun awaitCreated() {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            created != null
        }
    }

    @Test
    fun `a group cannot go on without a name, nor a member list without your own (GR-2, ID-2)`() {
        show()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        type("Name", "Trip")
        compose.onNodeWithText("Next").assertIsEnabled()
        next()
        compose.onNodeWithText("Who's in?").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        type("Your name", "Anna")
        compose.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun `going through the flow stores the group with its members and acts as its creator (GR-1, GR-2, ID-2)`() {
        show()
        type("Name", "Trip")
        type("Description", "Lisbon")
        compose.onNodeWithText("✈️").performClick()
        compose.onNodeWithContentDescription("Blue").performClick()
        next()

        type("Your name", "Anna")
        compose.onNodeWithText("Add member").performClick()
        type("Name", "Ben")
        compose.onNodeWithText("Add member").performClick()
        next()

        compose.onNodeWithText("Only on this device").assertExists()
        compose.onNodeWithText("Create group").performClick()
        awaitCreated()

        val group = assertNotNull(groups.load(assertNotNull(created)))
        assertEquals("Trip", group.name)
        assertEquals("Lisbon", group.description)
        assertEquals("✈️", group.emoji)
        assertEquals(GROUP_COLOURS[2], group.colour)
        assertEquals(listOf("Anna", "Ben"), group.members.values.map { it.name })
        assertEquals("Anna", group.members.getValue(assertNotNull(groups.actingAs(group.id))).name)
        assertEquals(false, shared)
    }

    @Test
    fun `choosing shared storage creates the group locally and asks for its backend (BE-1)`() {
        show()
        type("Name", "Trip")
        next()
        type("Your name", "Anna")
        next()
        compose.onNodeWithText("Shared storage (S3)").performClick()
        compose.onNodeWithText("Create group").performClick()
        awaitCreated()
        assertNotNull(groups.load(assertNotNull(created)))
        assertEquals(true, shared)
    }

    @Test
    fun `back steps through the flow and closes it from the first step (GR-2)`() {
        show()
        type("Name", "Trip")
        next()
        compose.onNodeWithText("Who's in?").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Preview").assertExists()
        assertEquals(0, closed)
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, closed)
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(2, closed)
    }

    @Test
    fun `a member removed before creating is not stored (ID-2)`() {
        show()
        type("Name", "Trip")
        next()
        type("Your name", "Anna")
        compose.onNodeWithText("Add member").performClick()
        type("Name", "Ben")
        compose.onNodeWithContentDescription("Remove Ben").performClick()
        next()
        compose.onNodeWithText("Create group").performClick()
        awaitCreated()
        val group = assertNotNull(groups.load(assertNotNull(created)))
        assertEquals(listOf("Anna"), group.members.values.map { it.name })
    }
}
