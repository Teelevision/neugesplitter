package eu.teele.neugesplitter.ui.invite

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import eu.teele.neugesplitter.ui.backend.Location
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class InviteScreenTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var ids = 0

    private fun id(n: Int) = "%032x".format(n)

    private val trip = GroupId.parse(id(9))
    private val settings = BackendSettings("https://s3.example", "splits", "", "auto", false, "AK")

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private lateinit var viewModel: InviteViewModel

    /** Group Trip, shared at [settings], whose secrets open as [secretKey] and [passphrase]. */
    private fun show(
        secretKey: String? = "SK",
        passphrase: String? = null,
        encrypted: Boolean = false,
    ) {
        val anna = Member(MemberId.parse(id(1)), "Anna")
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna), at) {
                EventId.parse(id(0x100 + ids++))
            },
            today,
        )
        groups.backends.connect(trip, settings)
        if (encrypted) {
            runBlocking {
                groups.local(trip, { today }, Dispatchers.Unconfined).rooted(byteArrayOf(1))
            }
        }
        viewModel = InviteViewModel(groups, trip, { secretKey }, { passphrase })
        compose.setContent { NeugesplitterTheme { InviteScreen(viewModel, onBack = {}) } }
        compose.waitUntil(5_000) { viewModel.state.value.loaded }
    }

    private fun secure() =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test
    fun `a shared group's invitation shows as a QR code that cannot be captured, once warned (BE-18)`() {
        show()
        compose.onNodeWithText("Show it only to them").assertExists()
        compose
            .onNodeWithText("The code holds the storage's secret key.", substring = true)
            .assertExists()
        compose.onNodeWithText("Screenshots of this screen are blocked.").assertExists()
        compose.onNodeWithContentDescription("QR code to join the group").assertDoesNotExist()
        assertNotEquals(0, secure())

        compose.onNodeWithText("Show the code").performClick()
        compose.onNodeWithContentDescription("QR code to join the group").assertExists()
        compose.onNodeWithText("Show it only to them").assertExists()
        assertEquals(
            Invitation(
                Location(
                    "https://s3.example",
                    "splits",
                    region = "auto",
                    accessKey = "AK",
                    secretKey = "SK",
                )
            ),
            viewModel.state.value.invitation,
        )
    }

    @Test
    fun `an encrypted group's invitation carries its passphrase (BE-18)`() {
        show(passphrase = "fluss kabel nebel", encrypted = true)
        compose
            .onNodeWithText(
                "The code holds the storage's secret key and the passphrase.",
                substring = true,
            )
            .assertExists()
        assertEquals("fluss kabel nebel", viewModel.state.value.invitation?.passphrase)
    }

    @Test
    fun `a passphrase that no longer opens is left out, and asked for when joining (BE-18)`() {
        show(passphrase = null, encrypted = true)
        compose.onNodeWithText("Show the code").performClick()
        compose
            .onNodeWithText("The passphrase no longer opens on this device", substring = true)
            .assertExists()
        compose.onNodeWithContentDescription("QR code to join the group").assertExists()
    }

    @Test
    fun `without the secret key there is nothing to invite with (BE-18)`() {
        show(secretKey = null)
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText("no longer opens on this device", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("QR code to join the group").assertDoesNotExist()
    }
}
