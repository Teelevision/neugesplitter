package eu.teele.neugesplitter.ui.export

import android.os.Looper
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.BackendSettings
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
class ExportImportTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var ids = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private var done: List<GroupId>? = null
    private lateinit var importing: ImportViewModel

    /** Trip, shared, with Anna's dinner for both of them in it. */
    private fun seed() {
        groups.create(
            Group.create(trip, "Trip", null, "⛺", null, listOf(anna, ben), at) {
                EventId.parse(id(0x100 + ids++))
            },
            today,
        )
        val dinner =
            Entry(
                id = EntryId.parse(id(0x50)),
                kind = Kind.EXPENSE,
                description = "Dinner",
                date = today,
                amount = Amount.ofUnits(3_000_000_000L),
                payers = Side.Equal(listOf(anna.id)),
                beneficiaries = Side.Equal(listOf(anna.id, ben.id)),
            )
        groups.append(
            trip,
            listOf(Event.EntryCreated(EventId.parse(id(0x150)), anna.id, at, dinner)),
            today,
        )
        groups.backends.connect(
            trip,
            BackendSettings("https://s3.example", "splits", "", "auto", false, "AK"),
        )
    }

    private fun await(condition: () -> Boolean) =
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }

    private fun awaitText(text: String) = await {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** Trip exported, with the passphrase it was encrypted with if [encrypted]. */
    private fun exported(encrypted: Boolean): Pair<ByteArray, String?> {
        val viewModel = ExportViewModel(groups, listOf(trip), { today }, Locale.ENGLISH)
        viewModel.encrypted(encrypted)
        if (encrypted) {
            viewModel.next()
            viewModel.saved(true)
        }
        val out = ByteArrayOutputStream()
        viewModel.save { out }
        await { viewModel.state.value.step == ExportStep.DONE }
        val passphrase = viewModel.state.value.words.joinToString(" ").takeIf { encrypted }
        return out.toByteArray() to passphrase
    }

    private fun show(file: ByteArray) {
        importing =
            ImportViewModel(
                groups,
                { ByteArrayInputStream(file) },
                { id(0x200 + ids++) },
                { today },
                { at },
            )
        compose.setContent {
            NeugesplitterTheme { ImportScreen(importing, onClose = {}, onDone = { done = it }) }
        }
    }

    @Test
    fun `an exported group comes back as a new group, unshared, as the member chosen (DA-3, DA-4)`() {
        seed()
        show(exported(encrypted = false).first)
        awaitText("Which groups do you want to import?")
        compose.onNodeWithText("Trip").assertExists()
        compose.onNodeWithText("2 members · 1 entry").assertExists()

        click("Next")
        awaitText("Who are you?")
        compose.onNodeWithText("Import").assertIsNotEnabled()
        click("Ben")
        click("Import")
        await { done != null }

        val copy = done!!.single()
        assertNotEquals(trip, copy)
        assertEquals(groups.load(trip)!!.copy(id = copy), groups.load(copy))
        assertEquals(ben.id, groups.actingAs(copy))
        assertNull(groups.backends.settings(copy))
    }

    @Test
    fun `an encrypted export asks for its passphrase until the right one is given (DA-4, PR-12)`() {
        seed()
        val (file, passphrase) = exported(encrypted = true)
        show(file)
        awaitText("This file is encrypted. Enter its passphrase.")
        compose.onAllNodesWithText("Passphrase")[0].performTextInput("wrong")
        click("Next")
        awaitText("This passphrase does not open the file.")

        importing.passphrase(assertNotNull(passphrase))
        click("Next")
        awaitText("Which groups do you want to import?")
        compose.onNodeWithText("Trip").assertExists()
    }

    @Test
    fun `importing a new member makes the user that member (DA-3, BE-6)`() {
        seed()
        show(exported(encrypted = false).first)
        awaitText("Which groups do you want to import?")
        click("Next")
        awaitText("Who are you?")
        click("New member")
        compose.onAllNodesWithText("Name")[0].performTextInput("Clara")
        click("Import")
        await { done != null }

        val copy = groups.load(done!!.single())!!
        val me = assertNotNull(groups.actingAs(copy.id))
        assertEquals("Clara", copy.members.getValue(me).name)
    }

    @Test
    fun `a file that is no export is refused, and nothing is imported`() {
        show("not a zip".encodeToByteArray())
        awaitText("File cannot be read")
        assertTrue(groups.unclaimed().isEmpty())
    }

    @Test
    fun `a file that cannot be written says so, and another can be chosen`() {
        seed()
        val viewModel = ExportViewModel(groups, listOf(trip), { today }, Locale.ENGLISH)
        viewModel.encrypted(false)
        viewModel.save {
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("full")
            }
        }
        await { viewModel.state.value.failed }
        assertTrue(viewModel.state.value.canSave)
        assertEquals("neugesplitter-2026-10-07.zip", viewModel.fileName())
    }
}
