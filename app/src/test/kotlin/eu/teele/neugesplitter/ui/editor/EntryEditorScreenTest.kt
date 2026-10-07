package eu.teele.neugesplitter.ui.editor

import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.balance.Transfer
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
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class EntryEditorScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val yesterday = EntryDate.parse("2026-10-06")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var issued = 0
    private var closed = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val clara = Member(MemberId.parse(id(3)), "Clara")
    private val trip = GroupId.parse(id(9))
    private val dinner = EntryId.parse(id(10))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    /** Anna's group, with her dinner for all three of them the day before, if [existing]. */
    private fun show(
        existing: Boolean = false,
        entry: EntryId? = null,
        duplicate: Boolean = false,
        settling: Transfer? = null,
    ) {
        groups.create(
            Group.create(trip, "Trip", null, "⛺", null, listOf(anna, ben, clara), at, ::eventId),
            today,
        )
        if (existing) {
            val meal =
                Entry(
                    id = dinner,
                    kind = Kind.EXPENSE,
                    description = "Dinner",
                    date = yesterday,
                    amount = cents(3000),
                    payers = Side.Equal(listOf(anna.id)),
                    beneficiaries = Side.Equal(listOf(anna.id, ben.id, clara.id)),
                )
            groups.append(trip, listOf(Event.EntryCreated(eventId(), anna.id, at, meal)), today)
        }
        val viewModel =
            EntryEditorViewModel(
                groups,
                trip,
                entry,
                duplicate,
                settling,
                "Settlement",
                '.',
                { id(0x200 + issued++) },
                { today },
                { at },
            )
        compose.setContent { NeugesplitterTheme { EntryEditorScreen(viewModel) { closed++ } } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Description").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun stored() = groups.load(trip)!!.entries

    private fun enterAmount(keys: String) {
        compose.onNodeWithText("Amount").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isNotEmpty()
        }
        for (c in keys) compose.onNode(hasText("$c") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("OK").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun save() {
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            closed > 0
        }
    }

    @Test
    fun `a new expense typed in is stored with its payer and beneficiaries (EN-1, EN-2, EN-4, EN-5)`() {
        show()
        compose.onNodeWithText("New entry").assertExists()
        compose.onNodeWithText("Description").performTextInput("Taxi")
        enterAmount("12.5")
        compose.onNodeWithText("12.50").assertExists()
        // The payers are listed first, then the beneficiaries.
        compose.onAllNodesWithText("Anna")[0].performClick()
        compose.onAllNodesWithText("Ben")[0].performClick()
        compose.onAllNodesWithText("Clara")[1].performClick()
        save()
        val entry = stored().values.single()
        assertEquals("Taxi", entry.description)
        assertEquals(Kind.EXPENSE, entry.kind)
        assertEquals(today, entry.date)
        assertEquals(cents(1250), entry.amount)
        assertEquals(Side.Equal(listOf(ben.id)), entry.payers)
        assertEquals(Side.Equal(listOf(anna.id, ben.id)), entry.beneficiaries)
        assertEquals(1, closed)
    }

    @Test
    fun `saving is refused until there is a description and an amount (EN-2)`() {
        show()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Add a description").assertExists()
        compose.onNodeWithText("Description").performTextInput("Taxi")
        compose.onNodeWithText("Enter an amount").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        enterAmount("5")
        compose.onNodeWithText("Save").assertIsEnabled()
        assertTrue(stored().isEmpty())
        assertEquals(0, closed)
    }

    @Test
    fun `editing an entry shows its values and saving stores the change (EN-9)`() {
        show(existing = true, entry = dinner)
        compose.onNodeWithText("Edit entry").assertExists()
        compose.onNodeWithText("Dinner").assertExists()
        compose.onNodeWithText("30.00").assertExists()
        compose.onNodeWithText("Description").performTextReplacement("Lunch")
        enterAmount("C24")
        save()
        val entry = stored().getValue(dinner)
        assertEquals("Lunch", entry.description)
        assertEquals(cents(2400), entry.amount)
        assertEquals(Side.Equal(listOf(anna.id)), entry.payers)
        assertEquals(yesterday, entry.date)
        assertEquals(1, stored().size)
    }

    @Test
    fun `a suggested transfer starts out as a transfer between the two and saves as one (BA-6, EN-7)`() {
        show(settling = Transfer(ben.id, anna.id, cents(1000)))
        compose.onNodeWithText("Settlement").assertExists()
        compose.onNodeWithText("10.00").assertExists()
        compose.onNodeWithText("From").assertExists()
        compose.onNodeWithText("To").assertExists()
        save()
        val entry = stored().values.single()
        assertEquals(Kind.TRANSFER, entry.kind)
        assertEquals("Settlement", entry.description)
        assertEquals(cents(1000), entry.amount)
        assertEquals(Side.Equal(listOf(ben.id)), entry.payers)
        assertEquals(Side.Equal(listOf(anna.id)), entry.beneficiaries)
    }

    @Test
    fun `a duplicate starts out as a copy dated today and saves as a new entry (EN-9)`() {
        show(existing = true, entry = dinner, duplicate = true)
        compose.onNodeWithText("New entry").assertExists()
        compose.onNodeWithText("Dinner").assertExists()
        compose.onNodeWithText("30.00").assertExists()
        save()
        val entries = stored()
        assertEquals(2, entries.size)
        assertEquals(yesterday, entries.getValue(dinner).date)
        val copy = entries.values.single { it.id != dinner }
        assertEquals("Dinner", copy.description)
        assertEquals(cents(3000), copy.amount)
        assertEquals(today, copy.date)
        assertEquals(entries.getValue(dinner).payers, copy.payers)
        assertEquals(entries.getValue(dinner).beneficiaries, copy.beneficiaries)
    }
}
