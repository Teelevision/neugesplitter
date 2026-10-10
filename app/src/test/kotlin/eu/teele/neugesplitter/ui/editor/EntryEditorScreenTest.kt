package eu.teele.neugesplitter.ui.editor

import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.balance.Transfer
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.DayOfWeek
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
    private val rent = SeriesId.parse(id(20))
    private val september = EntryDate.parse("2026-09-01")

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    /**
     * Anna's group, with her dinner for all three of them the day before, if [existing], and her
     * rent on the first of every month since August, if [renting], with the one due on [confirmed]
     * confirmed.
     */
    private fun show(
        existing: Boolean = false,
        entry: EntryId? = null,
        duplicate: Boolean = false,
        settling: Transfer? = null,
        ready: String = "Description",
        renting: Boolean = false,
        confirmed: String? = null,
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
        if (renting) {
            val template =
                Template(
                    Kind.EXPENSE,
                    "Rent",
                    cents(90000),
                    Side.Equal(listOf(anna.id)),
                    Side.Equal(listOf(anna.id, ben.id, clara.id)),
                )
            val monthly = Segment(Interval.MonthlyOn(setOf(1)), Mode.AUTOMATIC, template)
            val august = EntryDate.parse("2026-08-01")
            groups.append(
                trip,
                listOfNotNull(
                    Event.SeriesCreated(eventId(), anna.id, at, rent, august, monthly),
                    confirmed?.let {
                        Event.OccurrenceConfirmed(eventId(), anna.id, at, rent, EntryDate.parse(it))
                    },
                ),
                today,
            )
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
            compose.onAllNodesWithText(ready).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun stored() = groups.load(trip)!!.entries

    /** Types [keys] into the calculator, which a blank new entry has open already. */
    private fun enterAmount(keys: String) {
        if (compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText("Amount").performClick()
        }
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

    /** No input has focus, and the calculator has not opened by itself. */
    private fun assertQuiet() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("OK").assertCountEquals(0)
    }

    /** Taps the scrim at the top, which no sheet reaches. */
    private fun dismissSheet() {
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close sheet").performTouchInput {
            click(Offset(width / 2f, 10f))
        }
    }

    private fun scheme(label: String) = hasText(label) and isSelected()

    /** Switches Repeats on, which opens the recurrence sheet. */
    private fun repeat() {
        compose.onNodeWithContentDescription("Repeats").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Monthly").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun confirmRecurrence() {
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Monthly").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun descriptions() = groups.load(trip)!!.occurrences(today).map { it.entry.description }

    private fun rentOn(date: String) = EntryId.occurrence(rent, EntryDate.parse(date))

    private fun count(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher).fetchSemanticsNodes().size

    @Test
    fun `a new expense typed in is stored with its payer and beneficiaries (EN-1, EN-2, EN-4, EN-5)`() {
        show()
        compose.onNodeWithText("New entry").assertExists()
        enterAmount("12.5")
        compose.onNodeWithText("Description").performTextInput("Taxi")
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
    fun `saving is refused until there is an amount and a description (EN-2)`() {
        show()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Enter an amount").assertExists()
        enterAmount("5")
        compose.onNodeWithText("Add a description").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Description").performTextInput("Taxi")
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
        assertQuiet()
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
        assertQuiet()
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

    @Test
    fun `editing an entry focuses nothing and opens no calculator (EN-9)`() {
        show(existing = true, entry = dinner)
        compose.onNodeWithText("30.00").assertExists()
        assertQuiet()
    }

    @Test
    fun `a blank new entry opens the calculator first, and OK moves on to the description (EN-2)`() {
        show()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isNotEmpty()
        }
        enterAmount("7")
        compose.onNodeWithText("7.00").assertExists()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Description").assertIsFocused()
    }

    @Test
    fun `dismissing the first calculator leaves the entry as it is (EN-2)`() {
        show()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isNotEmpty()
        }
        dismissSheet()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isEmpty()
        }
        assertQuiet()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `the split is chosen on a bar, and equal needs no sheet (SP-1)`() {
        show()
        enterAmount("30")
        for (label in listOf("Equal", "Shares", "Adjusted", "Exact")) {
            compose.onNodeWithText(label).assertExists()
        }
        assertEquals(1, count(scheme("Equal")))
        compose.onNodeWithText("Equal").performClick()
        assertEquals(1, count(scheme("Equal")))
        compose.onAllNodesWithContentDescription("More shares for Anna").assertCountEquals(0)
        compose.onNodeWithText("Anna 10.00   Ben 10.00   Clara 10.00").assertExists()
    }

    @Test
    fun `a non-equal scheme opens the sheet with it chosen, and the entry changes only on OK (SP-1)`() {
        show()
        enterAmount("30")
        compose.onNodeWithText("Shares").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("3 shares in all").fetchSemanticsNodes().isNotEmpty()
        }
        // The sheet's tab is on shares, the bar under it still on equal.
        assertEquals(1, count(scheme("Shares")))
        assertEquals(1, count(scheme("Equal")))
        compose.onNodeWithContentDescription("More shares for Anna").performClick()
        compose.onNodeWithText("Anna 10.00   Ben 10.00   Clara 10.00").assertExists()
        dismissSheet()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("4 shares in all").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, count(scheme("Equal")))
        assertEquals(0, count(scheme("Shares")))
        compose.onNodeWithText("Anna 10.00   Ben 10.00   Clara 10.00").assertExists()
    }

    @Test
    fun `OK keeps the scheme, the same segment edits it again, and equal drops it (SP-1)`() {
        show()
        enterAmount("30")
        compose.onNodeWithText("Shares").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("3 shares in all").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("More shares for Anna").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("4 shares in all").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, count(scheme("Shares")))
        compose.onNodeWithText("Anna 15.00   Ben 7.50   Clara 7.50").assertExists()
        compose.onNodeWithText("Shares").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("4 shares in all").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("4 shares in all").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Equal").performClick()
        assertEquals(1, count(scheme("Equal")))
        compose.onAllNodesWithContentDescription("More shares for Anna").assertCountEquals(0)
        compose.onNodeWithText("Anna 10.00   Ben 10.00   Clara 10.00").assertExists()
    }

    @Test
    @Config(qualifiers = "de-rDE-w360dp-h800dp")
    fun `the four schemes are labelled in German on a narrow screen (SP-1)`() {
        show(ready = "Beschreibung")
        enterAmount("30")
        for (label in listOf("Gleich", "Anteile", "Angepasst", "Exakt")) {
            compose.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun `a new recurring entry is a series whose first occurrence it is (RE-1, RE-2)`() {
        show()
        enterAmount("900")
        compose.onNodeWithText("Description").performTextInput("Rent")
        repeat()
        compose.onNodeWithText("Every month on the 7th").assertExists()
        compose.onNodeWithText("Weekly").performClick()
        compose.onNodeWithText("Every week on Wednesday").assertExists()
        compose.onNodeWithText("Fri").performClick()
        confirmRecurrence()
        compose.onNodeWithText("Every week on Wednesday and Friday").assertExists()
        save()
        val group = groups.load(trip)!!
        assertEquals(emptyMap(), group.entries)
        val segment = group.series.values.single().segment(today)!!
        assertEquals(
            Interval.WeeklyOn(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            segment.interval,
        )
        assertEquals(Mode.AUTOMATIC, segment.mode)
        val first = group.occurrences(today).single()
        assertEquals(today, first.due)
        assertEquals("Rent", first.entry.description)
        assertEquals(cents(90000), first.entry.amount)
    }

    @Test
    fun `a new suggested series counts its first occurrence, the one entered, at once (RE-3)`() {
        show()
        enterAmount("40")
        compose.onNodeWithText("Description").performTextInput("Cleaning")
        repeat()
        compose.onNodeWithText("Suggested").performClick()
        compose.onNodeWithText("Each one waits for someone to confirm it.").assertExists()
        confirmRecurrence()
        compose.onNodeWithText("Every month on the 7th · Suggested").assertExists()
        save()
        val group = groups.load(trip)!!
        assertEquals(Mode.SUGGESTED, group.series.values.single().segment(today)!!.mode)
        assertEquals(listOf(false), group.occurrences(today).map { it.pending })
    }

    @Test
    fun `an entry switched to repeating becomes the first occurrence of a series (RE-1)`() {
        show(existing = true, entry = dinner)
        repeat()
        compose.onNodeWithText("Every month on the 6th").assertExists()
        confirmRecurrence()
        save()
        val group = groups.load(trip)!!
        assertEquals(emptyMap(), group.entries)
        assertTrue(dinner in group.deleted)
        val first = group.occurrences(today).single()
        assertEquals(yesterday, first.due)
        assertEquals("Dinner", first.entry.description)
    }

    @Test
    fun `a change to an occurrence asks, and only this one amends it alone (RE-6, RE-15)`() {
        show(renting = true, entry = rentOn("2026-09-01"))
        compose.onNodeWithText("Every month on the 1st").assertExists()
        compose.onNodeWithText("Description").performTextReplacement("Rent, late")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Change recurring entry").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            closed > 0
        }
        assertEquals(listOf("Rent", "Rent, late", "Rent"), descriptions())
    }

    @Test
    fun `a change to an occurrence can go for the rest of its series too (RE-6)`() {
        show(renting = true, entry = rentOn("2026-09-01"))
        compose.onNodeWithText("Description").performTextReplacement("Rent, raised")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("This one and the following").performClick()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            closed > 0
        }
        assertEquals(listOf("Rent", "Rent, raised", "Rent, raised"), descriptions())
        val series = groups.load(trip)!!.series.getValue(rent)
        assertEquals("Rent, raised", series.segment(september)!!.template.description)
    }

    @Test
    fun `an occurrence switched off ends its series after it, without asking (RE-7)`() {
        show(renting = true, entry = rentOn("2026-09-01"))
        compose.onNodeWithContentDescription("Repeats").performScrollTo().performClick()
        compose.onNodeWithText("The series ends after this one").assertExists()
        save()
        val dues = groups.load(trip)!!.occurrences(today).map { it.due.toString() }
        assertEquals(listOf("2026-08-01", "2026-09-01"), dues)
    }

    @Test
    fun `a series somebody decided on later keeps its rule, and a change goes for this one alone (RE-6)`() {
        show(renting = true, entry = rentOn("2026-09-01"), confirmed = "2026-10-01")
        val fixed =
            "Later entries of this series were confirmed or edited, so it can change only from " +
                "the last of them."
        compose.onNodeWithText(fixed).assertExists()
        compose.onNodeWithText("Every month on the 1st").performScrollTo().performClick()
        compose.onNodeWithText("Weekly").assertDoesNotExist()

        // Off and on again, it repeats as it did, without the sheet.
        compose.onNodeWithContentDescription("Repeats").performClick()
        compose.onNodeWithText("The series ends after this one").assertExists()
        compose.onNodeWithContentDescription("Repeats").performClick()
        compose.onNodeWithText("Every month on the 1st").assertExists()
        compose.onNodeWithText("Weekly").assertDoesNotExist()

        compose.onNodeWithText("Description").performTextReplacement("Rent, raised")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("This one and the following").assertIsNotEnabled().performClick()
        compose.onNode(hasText(fixed) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            closed > 0
        }
        assertEquals(listOf("Rent", "Rent, raised", "Rent"), descriptions())
        val series = groups.load(trip)!!.series.getValue(rent)
        assertEquals(1, series.segments.size)
    }
}
