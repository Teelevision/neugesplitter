package eu.teele.neugesplitter.ui

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

    private fun show() {
        compose.setContent {
            NeugesplitterTheme {
                GroupScreen(onBack = { left++ }, onNew = { added++ }) { destination, back, modifier
                    ->
                    TextButton(onClick = back, modifier) { Text("showing $destination") }
                }
            }
        }
    }

    @Test
    fun `the group opens on its overview (ui-design 1)`() {
        show()
        compose.onNodeWithText("showing OVERVIEW").assertExists()
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
    fun `the centre action stays in the centre with settings on its right (ui-design 1)`() {
        show()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val action =
            compose.onNodeWithContentDescription("New entry").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        val people = compose.onNodeWithText("People").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, action.center.x, 1f)
        assertTrue(people.right < action.left)
        assertTrue(action.right < settings.left)
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
