package eu.teele.neugesplitter.ui.backend

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.GroupSync
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.Sealing
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import eu.teele.neugesplitter.work.NewKey
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class BackendSetupScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var ids = 0

    private fun id(n: Int) = "%032x".format(n)

    private val trip = GroupId.parse(id(9))
    private val context = RuntimeEnvironment.getApplication()
    private val groups = GroupRepository(AndroidSqliteDriver(Database.Schema, context, null))

    private class Connected(val settings: BackendSettings, val secretKey: String, val key: NewKey?)

    private var bucket = BucketStore()
    private var keys = 0
    private var refuse = false
    private val connected = mutableListOf<Connected>()
    private var done = false
    private var closed = false
    private lateinit var viewModel: BackendSetupViewModel

    private fun show() {
        val anna = Member(MemberId.parse(id(1)), "Anna")
        if (groups.load(trip) == null) {
            val created =
                Group.create(trip, "Trip", null, null, null, listOf(anna), at) {
                    EventId.parse(id(0x100 + ids++))
                }
            groups.create(created, today)
        }
        viewModel =
            BackendSetupViewModel(
                groups,
                trip,
                { today },
                Locale.ENGLISH,
                { settings, _ ->
                    require(" " !in settings.endpoint)
                    bucket
                },
                {
                    keys++
                    NewKey(KageCodec().generate(), byteArrayOf(1), it)
                },
                { _, settings, secretKey, key ->
                    connected += Connected(settings, secretKey, key)
                    !refuse
                },
            )
        compose.setContent {
            NeugesplitterTheme {
                BackendSetupScreen(viewModel, onClose = { closed = true }, onDone = { done = true })
            }
        }
    }

    private fun type(label: String, text: String) =
        compose.onAllNodesWithText(label)[0].performTextInput(text)

    private fun fill(endpoint: String = "s3.example") {
        type("Endpoint", endpoint)
        type("Bucket", "splits")
        type("Access key", "AK")
        type("Secret key", "SK")
    }

    /** Clicks [text] in the body, scrolled to, or in the bottom bar, which does not scroll. */
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** Waits for something the view model did off the main thread. */
    private fun awaitText(text: String) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun await(condition: () -> Boolean) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }

    private fun tested() {
        click("Test connection")
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !viewModel.state.value.busy
        }
    }

    @Test
    fun `an empty location is checked and shares the group unencrypted (BE-7, BE-9, PR-11)`() {
        show()
        compose.onNodeWithText("Test connection").assertIsNotEnabled()
        fill()
        tested()
        awaitText("Reachable")
        compose.onNodeWithText("Credentials valid").assertExists()
        compose.onNodeWithText("Simultaneous changes protected").assertExists()
        click("Next")
        compose.onNodeWithText("Empty").assertExists()
        compose.onNodeWithText("The group is created here.").assertExists()
        click("Next")
        compose.onNodeWithText("Next").assertIsNotEnabled()
        click("Without encryption")
        click("Next")
        compose.onNodeWithText("Unencrypted connection").assertDoesNotExist()
        click("Understood, set up")
        await { done }

        val only = connected.single()
        assertEquals(
            BackendSettings("https://s3.example", "splits", "", "us-east-1", false, "AK"),
            only.settings,
        )
        assertEquals("SK", only.secretKey)
        assertNull(only.key)
    }

    @Test
    fun `an encrypted group needs its passphrase saved, and a retry keeps the same key (PR-11, PR-12)`() {
        show()
        fill()
        tested()
        click("Next")
        click("Next")
        click("With passphrase")
        click("Next")
        compose.onNodeWithText("Passphrase").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        click("I have saved the passphrase.")
        click("Next")
        awaitText("Understood, set up")

        click("Back")
        click("Next")
        awaitText("Understood, set up")
        refuse = true
        click("Understood, set up")
        awaitText("Foreign data")
        click("Choose another location")
        tested()
        click("Next")
        click("Next")
        click("Next")
        click("Next")
        awaitText("Understood, set up")
        refuse = false
        click("Understood, set up")
        await { done }

        assertEquals(1, keys)
        val words = viewModel.state.value.words
        assertEquals(6, words.size)
        val key = assertNotNull(connected.last().key)
        assertEquals(words.joinToString(" "), String(key.passphrase))
        assertEquals(key, connected.first().key)
    }

    @Test
    fun `a passphrase of your own is warned about while weak (PR-12b)`() {
        show()
        fill()
        tested()
        click("Next")
        click("Next")
        click("With passphrase")
        click("Next")
        click("Enter my own passphrase")
        compose.onNode(hasSetTextAction()).performTextInput("trip")
        awaitText("Weak passphrase")
        compose.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun `plain HTTP is warned about before the credentials are sent (PR-5)`() {
        show()
        fill(endpoint = "http://nas.local:9000")
        tested()
        click("Next")
        click("Next")
        click("Without encryption")
        click("Next")
        compose.onNodeWithText("Unencrypted connection").assertExists()
        compose.onNodeWithText("Understood, set up").assertDoesNotExist()
        click("Continue anyway")
        await { done }
        assertEquals("http://nas.local:9000", connected.single().settings.endpoint)
    }

    @Test
    fun `foreign data is not overwritten, and another location can be chosen (BE-9)`() {
        bucket.objects["log/${ObjectNumber(0).name}"] = byteArrayOf(1, 2, 3)
        show()
        fill()
        tested()
        click("Next")
        compose.onNodeWithText("Foreign data").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        click("Choose another location")
        compose.onNodeWithText("Test connection").assertExists()
    }

    @Test
    fun `read access alone cannot create the group (BE-7)`() {
        bucket = BucketStore(writable = false)
        show()
        fill()
        tested()
        compose.onNodeWithText("Read access only").assertExists()
        compose.onNodeWithText("Not needed without write access.").assertExists()
        click("Next")
        compose
            .onNodeWithText("With read access only, the group cannot be created here.")
            .assertExists()
        compose.onNodeWithText("The group is created here.").assertDoesNotExist()
        compose.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun `storage that cannot refuse conflicting writes is not used (BE-12)`() {
        bucket = BucketStore(conditional = false)
        show()
        fill()
        tested()
        compose.onNodeWithText("Simultaneous changes not protected").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun `an unreachable location says so, and back returns to the fields (BE-7)`() {
        bucket = BucketStore(reachable = false)
        show()
        fill()
        tested()
        compose.onNodeWithText("Not reachable").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        click("Back")
        compose.onNodeWithText("Test connection").assertExists()
        click("Back")
        assertEquals(true, closed)
    }

    @Test
    fun `an endpoint that is no address is marked`() {
        show()
        fill(endpoint = "not an address")
        click("Test connection")
        compose.onNodeWithText("This is not an address the app can reach.").assertExists()
    }

    @Test
    fun `pasted settings fill the fields`() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "",
                "endpoint = s3.example\nbucket = splits\naccess_key_id = AK\nsecret = SK",
            )
        )
        show()
        click("Paste")
        await { viewModel.state.value.location.complete }
        compose.onNodeWithText("Test connection").assertIsEnabled()

        clipboard.setPrimaryClip(ClipData.newPlainText("", "hello"))
        click("Paste")
        awaitText("The clipboard holds no settings this app recognises.")
    }

    @Test
    fun `a location holding this group connects it without asking about encryption (BE-10)`() {
        show()
        runBlocking { GroupSync(bucket, groups.local(trip, { today }), Sealing.Plain).pass() }
        fill()
        tested()
        click("Next")
        compose.onNodeWithText("This group").assertExists()
        click("Next")
        click("Understood, set up")
        await { done }
        assertNull(connected.single().key)
    }
}
