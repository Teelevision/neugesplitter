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
class SeriesScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var back = 0
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
    private fun show(vararg after: Event) {
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
                SeriesScreen(viewModel, onBack = { back++ }, onOpen = { opened += it })
            }
        }
        awaitText("Every month on the 1st · Automatic")
    }

    private fun date(text: String) = EntryDate.parse(text)

    private fun awaitText(text: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun series() = groups.load(trip)!!.series.getValue(rent)

    @Test
    fun `a series shows what it produces, how it repeats, and the entries it made (RE-9)`() {
        show()
        compose.onNodeWithText("Running").assertExists()
        compose.onNodeWithText("Its entries").assertExists()
        // The header, and one row for each of August, September and October.
        assertEquals(4, compose.onAllNodesWithText("Rent").fetchSemanticsNodes().size)
        assertEquals(4, compose.onAllNodesWithText("Paid by Anna").fetchSemanticsNodes().size)
        assertEquals(4, compose.onAllNodesWithText("900.00").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Rent")[1].performClick()
        assertEquals(listOf(EntryId.occurrence(rent, date("2026-10-01"))), opened)
    }

    @Test
    fun `pausing stops it from tomorrow, and resuming takes that back (RE-7)`() {
        show()
        compose.onNodeWithText("Pause").performClick()
        awaitText("Stops from Thursday, October 8, 2026")
        assertEquals(date("2026-10-08"), series().stop(today))
        assertEquals(3, series().occurrences(today).size)

        compose.onNodeWithText("Resume").performClick()
        awaitText("Running")
        assertEquals(null, series().stop(today))
    }

    @Test
    fun `a series stopped in the past resumes from today and skips what fell due meanwhile (RE-7)`() {
        show(Event.SeriesEnded(eventId(), anna.id, at, rent, date("2026-09-15")))
        compose.onNodeWithText("Stopped since Tuesday, September 15, 2026").assertExists()
        compose.onNodeWithText("Stop from a date").assertDoesNotExist()
        assertEquals(3, compose.onAllNodesWithText("Rent").fetchSemanticsNodes().size)

        compose.onNodeWithText("Resume").performClick()
        awaitText("Running")
        val dues = series().occurrences(date("2026-11-01")).map { it.due.toString() }
        assertEquals(listOf("2026-08-01", "2026-09-01", "2026-11-01"), dues)
    }

    @Test
    fun `stopping from a date offers tomorrow and leaves what came before standing (RE-7)`() {
        show()
        compose.onNodeWithText("Stop from a date").performClick()
        compose.onNodeWithText("OK").performClick()
        awaitText("Stops from Thursday, October 8, 2026")
        assertEquals(3, series().occurrences(date("2026-11-01")).size)
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
