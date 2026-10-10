package eu.teele.neugesplitter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class GroupScreenTest {

    @get:Rule val compose = createComposeRule()

    private var added = 0
    private var left = 0

    private fun show(attention: Boolean = false) {
        compose.setContent {
            NeugesplitterTheme {
                GroupScreen(onBack = { left++ }, onNew = { added++ }, attention = attention) {
                    destination,
                    back,
                    go,
                    modifier ->
                    Column(modifier) {
                        TextButton(onClick = back) { Text("showing $destination") }
                        TextButton(onClick = { go(Destination.ACTIVITY) }) { Text("to activity") }
                    }
                }
            }
        }
    }

    @Test
    fun `activity carries no badge while nothing there needs attention (ui-design 4)`() {
        show()
        compose
            .onNodeWithContentDescription("Needs attention", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `activity carries a badge while something there needs attention (ui-design 4)`() {
        show(attention = true)
        val badge =
            compose
                .onNodeWithContentDescription("Needs attention", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val action =
            compose.onNodeWithContentDescription("New entry").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue(badge.center.x in action.right..settings.left)
    }

    @Test
    fun `the group opens on its overview (ui-design 1)`() {
        show()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
    }

    @Test
    fun `a destination can switch to another, and back returns to the overview (ui-design 4)`() {
        show()
        compose.onNodeWithText("to activity").performClick()
        compose.onNodeWithText("showing ACTIVITY").performClick()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
        assertEquals(0, left)
    }

    @Test
    fun `the navigation bar switches between overview and people (ui-design 1)`() {
        show()
        compose.onNodeWithText("People").performClick()
        compose.onNodeWithText("showing PEOPLE").assertExists()
        compose.onNodeWithText("Overview").performClick()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
    }

    @Test
    fun `the navigation bar switches to settings and back (ui-design 1)`() {
        show()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("showing SETTINGS").assertExists()
        compose.onNodeWithText("Overview").performClick()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
    }

    @Test
    fun `the navigation bar switches to activity and back (ui-design 1)`() {
        show()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("showing ACTIVITY").assertExists()
        compose.onNodeWithText("Overview").performClick()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
    }

    @Test
    fun `the centre action stays in the centre between people and activity (ui-design 1)`() {
        show()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val action =
            compose.onNodeWithContentDescription("New entry").fetchSemanticsNode().boundsInRoot
        val activity = compose.onNodeWithText("Activity").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        val people = compose.onNodeWithText("People").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, action.center.x, 1f)
        assertTrue(people.right < action.left)
        assertTrue(action.right < activity.left)
        assertTrue(activity.right < settings.left)
    }

    @Test
    fun `the centre action starts a new entry (ui-design 1)`() {
        show()
        compose.onNodeWithContentDescription("New entry").performClick()
        assertEquals(1, added)
    }

    @Test
    fun `back from people returns to the overview (ui-design 1)`() {
        show()
        compose.onNodeWithText("People").performClick()
        compose.onNodeWithText("showing PEOPLE").assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
    }

    @Test
    fun `back from settings returns to the overview (ui-design 1)`() {
        show()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("showing SETTINGS").assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
    }

    @Test
    fun `a destination's back arrow returns to the overview, and the overview's leaves (ui-design 1)`() {
        show()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("showing SETTINGS").performClick()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
        assertEquals(0, left)
        compose.onNodeWithText("showing OVERVIEW").performClick()
        assertEquals(1, left)
    }
}
