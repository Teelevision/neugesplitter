package eu.teele.neugesplitter.ui.join

import android.os.Looper
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.crypto.AgeCodec
import eu.teele.neugesplitter.format.crypto.GroupKey
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.storage.settings.Secrets
import eu.teele.neugesplitter.storage.settings.SecretsDatabase
import eu.teele.neugesplitter.storage.settings.Vault
import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.GroupSync
import eu.teele.neugesplitter.sync.Keyring
import eu.teele.neugesplitter.sync.MemoryLog
import eu.teele.neugesplitter.sync.Sealing
import eu.teele.neugesplitter.ui.backend.Location
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import eu.teele.neugesplitter.work.Polls
import eu.teele.neugesplitter.work.Syncing
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class JoinScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var ids = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))
    private val passphrase = "fluss kabel nebel"

    private val context = RuntimeEnvironment.getApplication()
    private val groups = GroupRepository(AndroidSqliteDriver(Database.Schema, context, null))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bucket = BucketStore()

    /** age without scrypt: a root object is its passphrase behind a dash. */
    private class FastCodec(private val key: GroupKey) : AgeCodec by KageCodec() {
        override fun unlock(root: ByteArray, passphrase: CharArray): GroupKey {
            if (root.decodeToString() != "-${String(passphrase)}") throw WrongPassphrase()
            return key
        }
    }

    private object Plain : Vault {
        override fun seal(plain: ByteArray) = plain

        override fun open(sealed: ByteArray) = sealed
    }

    private object NoPolls : Polls {
        override fun schedule(id: GroupId, interval: Duration) = Unit

        override fun cancel(id: GroupId) = Unit

        override fun cancelAll() = Unit
    }

    private val key = KageCodec().generate()
    private val codec = FastCodec(key)
    private val secrets = Secrets(AndroidSqliteDriver(SecretsDatabase.Schema, context, null), Plain)

    private val created =
        Group.create(trip, "Trip", null, "⛺", null, listOf(anna, ben), at) {
            EventId.parse(id(0x100 + ids++))
        }

    private var done: GroupId? = null
    private var here: Location? = null
    private lateinit var viewModel: JoinViewModel

    @After
    fun stop() {
        scope.cancel()
    }

    /** Group Trip at [bucket], as another device shared it. */
    private fun sharedElsewhere(encrypted: Boolean = false) = runBlocking {
        val local = MemoryLog().apply { record(*created.toTypedArray()) }
        val sealing = if (encrypted) Sealing.Encrypted(codec, key) else Sealing.Plain
        val sync = GroupSync(bucket, local, sealing)
        if (encrypted) sync.publish("-$passphrase".encodeToByteArray())
        sync.pass()
    }

    private fun show() {
        val syncing =
            Syncing(
                groups,
                secrets,
                Keyring(codec),
                codec,
                scope,
                { today },
                { at },
                { _, _ -> bucket },
                NoPolls,
            )
        viewModel =
            JoinViewModel(groups, { _, _ -> bucket }, syncing, { id(0x200 + ids++) }, { at })
        compose.setContent {
            NeugesplitterTheme {
                JoinScreen(
                    viewModel,
                    onClose = {},
                    onDone = { done = it },
                    onCreateHere = { here = it },
                )
            }
        }
    }

    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

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

    /** Fills in a location, tests it, and goes on to what it holds. */
    private fun located() {
        compose.onAllNodesWithText("Endpoint")[0].performTextInput("s3.example")
        compose.onAllNodesWithText("Bucket")[0].performTextInput("splits")
        compose.onAllNodesWithText("Access key")[0].performTextInput("AK")
        compose.onAllNodesWithText("Secret key")[0].performTextInput("SK")
        click("Test connection")
        await { !viewModel.state.value.busy }
        awaitText("Reachable")
        click("Next")
    }

    @Test
    fun `a shared group is fetched and joined as one of its members (BE-5, BE-6, SY-7)`() {
        sharedElsewhere()
        show()
        located()
        awaitText("Group found")
        compose.onNodeWithText("Trip").assertExists()
        compose.onNodeWithText("2 members · 0 entries").assertExists()
        compose.onNodeWithText("Join").assertIsNotEnabled()

        click("Ben")
        click("Join")
        await { done != null }

        assertEquals(trip, done)
        assertEquals(ben.id, groups.actingAs(trip))
        assertEquals(created, groups.events(trip))
        assertEquals(
            BackendSettings("https://s3.example", "splits", "", "us-east-1", false, "AK"),
            groups.backends.settings(trip),
        )
        assertEquals("SK", secrets.secretKey(trip))
    }

    @Test
    fun `a user not yet in the group joins as a new member (BE-6)`() {
        sharedElsewhere()
        show()
        located()
        awaitText("Group found")
        click("New member")
        compose.onNodeWithText("Join").assertIsNotEnabled()
        compose.onAllNodesWithText("Name")[0].performTextInput("Clara")
        click("Join")
        await { done != null }

        val added = groups.events(trip).last() as Event.MemberAdded
        assertEquals("Clara", added.member.name)
        assertEquals(added.member.id, groups.actingAs(trip))
        assertEquals("Clara", groups.load(trip)?.members?.get(added.member.id)?.name)
    }

    @Test
    fun `an encrypted group asks for its passphrase until the right one is given (BE-6, TR-26)`() {
        sharedElsewhere(encrypted = true)
        show()
        located()
        awaitText("This group is encrypted. Enter the passphrase you were given.")
        compose.onAllNodesWithText("Passphrase")[0].performTextInput("falsch")
        click("Next")
        awaitText("This passphrase does not open the group.")

        viewModel.passphrase(passphrase)
        click("Next")
        awaitText("Group found")
        click("Anna")
        click("Join")
        await { done != null }

        assertEquals(created, groups.events(trip))
        assertContentEquals(passphrase.toCharArray(), secrets.passphrase(trip))
    }

    @Test
    fun `going back from a fetched group keeps none of it (BE-6)`() {
        sharedElsewhere()
        show()
        located()
        awaitText("Group found")
        assertNotNull(groups.load(trip))

        click("Back")
        await { groups.load(trip) == null }
        assertEquals(emptyList(), groups.unclaimed())
    }

    @Test
    fun `an empty location offers a new group there, with the location handed over (BE-9)`() {
        show()
        located()
        awaitText("No content found")
        click("Create a new group here")

        assertEquals("s3.example", here?.endpoint)
        assertEquals("SK", here?.secretKey)
    }

    @Test
    fun `an empty location with read access only offers no new group (BE-7, BE-9)`() {
        bucket = BucketStore(writable = false)
        show()
        located()
        awaitText("No content found")
        compose
            .onNodeWithText(
                "This location is empty. With read access only, no group can be created here."
            )
            .assertExists()
        compose.onNodeWithText("Create a new group here").assertDoesNotExist()
        compose.onNodeWithText("Join").assertIsNotEnabled()
    }

    @Test
    fun `a location holding something else is not joined (BE-9)`() {
        bucket.objects["log/0000000"] = "{\"a\":1}".encodeToByteArray()
        show()
        located()
        awaitText("Unknown data")
        compose.onNodeWithText("Join").assertIsNotEnabled()
    }

    @Test
    fun `a group already on this device is opened, and nothing is changed (BE-9)`() {
        sharedElsewhere()
        groups.create(created, today)
        val actingAs = groups.actingAs(trip)
        show()
        located()
        awaitText("Already on this device")
        click("Open group")
        await { done != null }

        assertEquals(trip, done)
        assertEquals(actingAs, groups.actingAs(trip))
        assertNull(groups.backends.settings(trip))
    }
}
