package eu.teele.neugesplitter.ui.series

import android.os.Looper
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Impact
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Stop
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
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
// Tall enough for every row to be composed.
@Config(qualifiers = "en-rUS-w411dp-h1400dp")
class SeriesScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var back = 0
    private var changes = 0
    private val opened = mutableListOf<EntryId>()

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))
    private val rent = SeriesId.parse(id(20))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private val monthly =
        Segment(
            Interval.MonthlyOn(setOf(1)),
            Mode.AUTOMATIC,
            Template(
                Kind.EXPENSE,
                "Rent",
                Amount.ofUnits(90_000_000_000L),
                Side.Equal(listOf(anna.id)),
                Side.Equal(listOf(anna.id, ben.id)),
            ),
        )

    /** Anna's rent, monthly on the 1st from 1 August, so three are due by 7 October. */
    private fun show(vararg after: Event, repeats: String = "Every month on the 1st · Automatic") {
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna, ben), at, ::eventId),
            today,
        )
        val created = Event.SeriesCreated(eventId(), anna.id, at, rent, date("2026-08-01"), monthly)
        groups.append(trip, listOf(created) + after, today)
        val viewModel =
            SeriesViewModel(groups, trip, rent, { id(0x200 + events++) }, { today }, { at })
        compose.setContent {
            NeugesplitterTheme {
                SeriesScreen(
                    viewModel,
                    onBack = { back++ },
                    onChange = { changes++ },
                    onOpen = { opened += it },
                )
            }
        }
        awaitText(repeats)
    }

    private fun date(text: String) = EntryDate.parse(text)

    private fun awaitText(text: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun series() = groups.load(trip)!!.series.getValue(rent)

    @Test
    fun `a series shows what it produces, how it repeats, and the entries it made under their days (RE-9)`() {
        show()
        compose.onNodeWithText("Running").assertExists()
        compose.onNodeWithText("Entries and history").assertExists()
        compose.onNodeWithText("Thursday, October 1, 2026").assertExists()
        compose.onNodeWithText("Tuesday, September 1, 2026").assertExists()
        // The header, and one row for each of August, September and October.
        assertEquals(4, compose.onAllNodesWithText("Rent").fetchSemanticsNodes().size)
        assertEquals(4, compose.onAllNodesWithText("Paid by Anna").fetchSemanticsNodes().size)
        assertEquals(4, compose.onAllNodesWithText("900.00").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Rent")[1].performClick()
        assertEquals(listOf(EntryId.occurrence(rent, date("2026-10-01"))), opened)
    }

    private fun inDialog(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    @Test
    fun `a series says when it is next due and what became of a due date without an entry (RE-5, RE-9)`() {
        show(Event.OccurrenceDeleted(eventId(), anna.id, at, rent, date("2026-09-01")))
        compose.onNodeWithText("Next due on Sunday, November 1, 2026").assertExists()
        compose.onNodeWithText("Tuesday, September 1, 2026").assertExists()
        compose.onNodeWithText("Left out: declined or deleted").assertExists()
        assertEquals(3, compose.onAllNodesWithText("Rent").fetchSemanticsNodes().size)
    }

    @Test
    fun `what was done to a series is listed between its entries, on the day it was done (RE-9, HI-2)`() {
        val later = at.plusSeconds(3600)
        show(Event.OccurrenceDeleted(eventId(), ben.id, later, rent, date("2026-09-01")))
        compose.onNodeWithText("Anna added 'Rent', repeating").assertExists()
        compose.onNodeWithText("Every month on the 1st · 900.00").assertExists()
        compose.onNodeWithText("Ben left out 'Rent'").assertExists()
        compose.onNodeWithText("Due Sep 1, 2026").assertExists()
        val top = { text: String ->
            compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue(top("Wednesday, October 7, 2026") < top("Ben left out 'Rent'"))
        assertTrue(top("Ben left out 'Rent'") < top("Anna added 'Rent', repeating"))
        assertTrue(top("Anna added 'Rent', repeating") < top("Thursday, October 1, 2026"))
    }

    @Test
    fun `a running series can be changed from a date on (RE-6)`() {
        show()
        compose.onNodeWithText("Change").performClick()
        assertEquals(1, changes)
    }

    @Test
    fun `a suggestion nobody decided yet is listed as waiting (RE-4, RE-9)`() {
        val suggested = monthly.copy(mode = Mode.SUGGESTED)
        show(
            Event.SeriesAmended(eventId(), anna.id, at, rent, date("2026-10-01"), suggested),
            repeats = "Every month on the 1st · Suggested",
        )
        compose.onNodeWithText("Thursday, October 1, 2026").assertExists()
        compose
            .onNodeWithText("Suggested, waiting to be confirmed or declined in Activity")
            .assertExists()
        assertEquals(3, compose.onAllNodesWithText("Rent").fetchSemanticsNodes().size)
    }

    @Test
    fun `stopping offers today, says what stays, and leaves only continuing (RE-7)`() {
        show()
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("Stop this series").assertExists()
        inDialog("Wednesday, October 7, 2026").assertExists()
        compose.onNodeWithText("Entries it already added stay.").assertExists()
        inDialog("Stop").performClick()
        awaitText("Stopped since Wednesday, October 7, 2026")
        assertEquals(listOf(Stop(today)), series().stops)
        assertEquals(3, series().occurrences(date("2026-11-01")).size)
        compose.onNodeWithText("Pause").assertDoesNotExist()
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onNodeWithText("Continue").assertExists()
    }

    @Test
    fun `a pause skips the next occurrence by default and then goes on by itself (RE-7)`() {
        show()
        compose.onNodeWithText("Pause").performClick()
        inDialog("Wednesday, October 7, 2026").assertExists()
        compose.onNodeWithText("Monday, November 2, 2026").assertExists()
        inDialog("Pause").performClick()
        awaitText("Paused, continues on Monday, November 2, 2026")
        val dues = series().occurrences(date("2026-12-01")).map { it.due.toString() }
        assertEquals(listOf("2026-08-01", "2026-09-01", "2026-10-01", "2026-12-01"), dues)
    }

    @Test
    fun `a series stopped in the past continues from today and skips what fell due meanwhile (RE-7)`() {
        show(Event.SeriesStopped(eventId(), anna.id, at, rent, date("2026-09-15")))
        compose.onNodeWithText("Stopped since Tuesday, September 15, 2026").assertExists()
        compose.onNodeWithText("Pause").assertDoesNotExist()
        assertEquals(3, compose.onAllNodesWithText("Rent").fetchSemanticsNodes().size)

        compose.onNodeWithText("Continue").performClick()
        inDialog("Wednesday, October 7, 2026").assertExists()
        inDialog("Continue").performClick()
        awaitText("Running")
        val dues = series().occurrences(date("2026-11-01")).map { it.due.toString() }
        assertEquals(listOf("2026-08-01", "2026-09-01", "2026-11-01"), dues)
    }

    @Test
    fun `a stop counts what it would remove, and not what somebody confirmed (RE-7)`() {
        show(Event.OccurrenceConfirmed(eventId(), anna.id, at, rent, date("2026-09-01")))
        val detail = SeriesDetail(today, series(), monthly, emptyList(), Scale.DEFAULT, emptyList())
        assertEquals(date("2026-08-02"), detail.earliestStop)
        assertEquals(Impact(1, 0, 0), detail.change(series().stopped(Stop(date("2026-08-15")))))
        val stopped = series().stopped(Stop(date("2026-08-15")))
        assertEquals(Impact(0, 1, 0), detail.copy(series = stopped).change(series()))
    }

    @Test
    fun `a pause long over can still be removed, and what it held back is added (RE-7)`() {
        val pause =
            Event.SeriesStopped(
                eventId(),
                anna.id,
                at,
                rent,
                date("2026-08-15"),
                date("2026-09-15"),
            )
        show(pause)
        compose.onNodeWithText("Running").assertExists()
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose
            .onNodeWithText(
                "From Saturday, August 15, 2026, continues on Tuesday, September 15, 2026"
            )
            .assertExists()

        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove this pause?").assertExists()
        compose.onNodeWithText("1 entry that fell due meanwhile is added.").assertExists()
        inDialog("Remove").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            series().stops.isEmpty()
        }
        val dues = series().occurrences(today).map { it.due.toString() }
        assertEquals(listOf("2026-08-01", "2026-09-01", "2026-10-01"), dues)
    }

    @Test
    fun `deleting asks first, then removes it with everything it produced (RE-7)`() {
        show()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this series?").assertExists()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            back > 0
        }
        assertTrue(series().deleted)
        assertTrue(groups.load(trip)!!.occurrences(today).isEmpty())
    }
}
