package eu.teele.neugesplitter.ui

import android.os.Looper
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import eu.teele.neugesplitter.AppGraph
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class AppTest {

    @get:Rule val compose = createComposeRule()

    private val graph = AppGraph(RuntimeEnvironment.getApplication())
    private var ids = 0

    private fun id(n: Int) = "%032x".format(n)

    @Before
    fun work() =
        WorkManagerTestInitHelper.initializeTestWorkManager(
            RuntimeEnvironment.getApplication(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )

    private fun awaitText(text: String) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun trip() {
        val anna = Member(MemberId.parse(id(1)), "Anna")
        val created =
            Group.create(
                GroupId.parse(id(9)),
                "Trip",
                null,
                null,
                null,
                listOf(anna),
                Instant.parse("2026-10-08T12:00:00Z"),
            ) {
                EventId.parse(id(0x100 + ids++))
            }
        graph.groups.create(created, graph.today())
    }

    @Test
    fun `back from a group's settings returns to its overview, then to the group list (ui-design 1)`() {
        trip()
        compose.setContent { NeugesplitterTheme { App(graph) } }

        awaitText("Trip")
        compose.onNodeWithText("Trip").performClick()
        awaitText("Settings")
        compose.onNodeWithText("Settings").performClick()
        awaitText("Delete group from this device")
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Overview").assertExists()
        compose.onNodeWithText("Delete group from this device").assertDoesNotExist()

        compose.onNodeWithText("People").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Overview").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Overview").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Trip").assertExists()
    }

    @Test
    fun `deleting a group from its settings returns to the group list (GR-11)`() {
        trip()
        compose.setContent { NeugesplitterTheme { App(graph) } }

        awaitText("Trip")
        compose.onNodeWithText("Trip").performClick()
        awaitText("Settings")
        compose.onNodeWithText("Settings").performClick()
        compose
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("Delete group from this device"))
        compose.onNodeWithText("Delete group from this device").performClick()
        compose.onNodeWithText("Delete").performClick()

        awaitText("A group stays on this device or is shared.")
    }
}
