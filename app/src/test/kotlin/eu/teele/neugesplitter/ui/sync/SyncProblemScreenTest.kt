package eu.teele.neugesplitter.ui.sync

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.storage.settings.Failure
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class SyncProblemScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var backs = 0
    private val checked = mutableListOf<GroupId>()

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val trip = GroupId.parse(id(9))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + events++))

    /** Anna's shared group "Trip", halted at object 3 for [reason]. */
    private fun show(reason: Halt) {
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna), at, ::eventId),
            today,
        )
        groups.backends.connect(
            trip,
            BackendSettings("https://s3.example", "splits", "", "auto", false, "AK"),
        )
        groups.backends.halted(trip, Upload.Halted(ObjectNumber(3), reason))
        val viewModel = SyncProblemViewModel(groups, trip) { checked += it }
        compose.setContent {
            NeugesplitterTheme { SyncProblemScreen(viewModel, onBack = { backs++ }) }
        }
        awaitText("Stopped at object 0000003")
    }

    private fun awaitText(text: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `a lost object is said plainly, with the group still usable here (SY-15)`() {
        show(Halt.TRUNCATED)
        compose
            .onNodeWithText(
                "The storage no longer holds part of the history this device already has."
            )
            .assertExists()
        compose.onNodeWithText("The group still works fully", substring = true).assertExists()
        compose.onNodeWithText("The storage provider's own tools", substring = true).assertExists()
        assertEquals(0, backs)
    }

    @Test
    fun `an object from a newer version asks for an update rather than a repair (SY-15)`() {
        show(Halt.UNSUPPORTED)
        compose.onNodeWithText("A newer version of the app wrote to this group.").assertExists()
        compose.onNodeWithText("Update the app, and syncing resumes.").assertExists()
        compose
            .onNodeWithText("The storage provider's own tools", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `checking again runs a pass, and one that cannot reach the storage says so (SY-9)`() {
        show(Halt.GAP)
        compose.onNodeWithText("Check again").performClick()
        assertEquals(listOf(trip), checked)
        groups.backends.failed(trip, Failure.UNREACHABLE)
        awaitText("Not reachable")
        compose.onNodeWithText("Stopped at object 0000003").assertExists()
    }

    @Test
    fun `once the storage agrees again the screen says so and is left with done (SY-15)`() {
        show(Halt.TRUNCATED)
        groups.backends.succeeded(trip, at)
        awaitText("Syncing again")
        compose.onNodeWithText("Check again").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, backs)
    }
}
