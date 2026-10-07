package eu.teele.neugesplitter.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class GroupScreenTest {

    @get:Rule val compose = createComposeRule()

    private var added = 0

    private fun show() {
        compose.setContent {
            NeugesplitterTheme {
                GroupScreen(onNew = { added++ }) { destination, modifier ->
                    Text("showing $destination", modifier)
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
}
