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
                "Correction",
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

    /** A made-up Splitwise export: Anna's groceries for both, and Ben paying part of it back. */
    private fun splitwise(total: String = "10.00", currency: String = "EUR") =
        ("\uFEFFDate,Description,Category,Cost,Currency,Anna,Ben\r\n" +
                "2024-01-05,\"Rice, beans\",Groceries,30.00,EUR,15.00,-15.00\r\n" +
                "2024-01-07,Ben B. paid Anna A.,Payment,5.00,$currency,-5.00,5.00\r\n" +
                "\r\n" +
                "2024-01-08,Total balance, , ,EUR,$total,-$total\r\n")
            .encodeToByteArray()

    @Test
    fun `a Splitwise export becomes a new group, named by the user, as the member chosen (DA-5)`() {
        show(splitwise())
        awaitText("Splitwise export")
        compose.onNodeWithText("2 members").assertExists()
        compose.onNodeWithText("1 entry").assertExists()
        compose.onNodeWithText("1 transfer").assertExists()
        compose.onNodeWithText("Balances match").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()

        compose.onAllNodesWithText("Name")[0].performTextInput("Flat")
        click("Next")
        awaitText("Who are you?")
        click("Ben")
        click("Import")
        await { done != null }

        val group = groups.load(done!!.single())!!
        val me = assertNotNull(groups.actingAs(group.id))
        assertEquals("Flat", group.name)
        assertEquals("Ben", group.members.getValue(me).name)
        assertEquals(listOf("Ben", "Anna"), group.members.values.map { it.name })
        assertEquals(listOf(Kind.EXPENSE, Kind.TRANSFER), group.entries.values.map { it.kind })
        assertEquals(
            mapOf(me to Amount.ofUnits(-1_000_000_000L)),
            group.settlement(today).filterKeys { it == me },
        )
        assertNull(groups.backends.settings(group.id))
    }

    @Test
    fun `a Splitwise export whose totals do not come out is corrected by one more entry (DA-5)`() {
        show(splitwise(total = "10.03"))
        awaitText("Balances differ")
        awaitText(
            "A balance comes out up to 0.03 off the export's totals. One more entry is added " +
                "that corrects it; delete it after the import if you do not want it."
        )
        compose.onAllNodesWithText("Name")[0].performTextInput("Flat")
        click("Next")
        awaitText("Who are you?")
        click("Ben")
        click("Import")
        await { done != null }

        val group = groups.load(done!!.single())!!
        assertEquals(3, group.entries.size)
        assertEquals("Correction", group.entries.values.last().description)
        assertEquals(
            setOf(Amount.ofUnits(1_003_000_000L), Amount.ofUnits(-1_003_000_000L)),
            group.settlement(today).values.toSet(),
        )
    }

    @Test
    fun `a Splitwise export in several currencies is refused (DA-5)`() {
        show(splitwise(currency = "USD"))
        awaitText("Several currencies")
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
