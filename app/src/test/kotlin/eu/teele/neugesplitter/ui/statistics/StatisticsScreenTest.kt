package eu.teele.neugesplitter.ui.statistics

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.R
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
import eu.teele.neugesplitter.ui.overview.EntryFilter
import eu.teele.neugesplitter.ui.overview.Listing
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class StatisticsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))
    private val groups = GroupRepository(AndroidSqliteDriver(Database.Schema, context, null))

    private fun id(number: Int) = "%032x".format(number)

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun text(id: Int) = context.getString(id)

    private fun viewModel(withEntries: Boolean = true): StatisticsViewModel {
        groups.create(
            Group.create(trip, "Trip", null, "⛺", null, listOf(anna, ben), at, ::eventId),
            today,
        )
        if (withEntries) {
            val everyone = Side.Equal(listOf(anna.id, ben.id))
            val entries =
                listOf(
                    entry(10, Kind.EXPENSE, "Old dinner", "2024-03-14", 3000, anna, everyone),
                    entry(11, Kind.EXPENSE, "Groceries", "2026-05-20", 1200, ben, everyone),
                    entry(12, Kind.INCOME, "Refund", "2026-09-10", 500, anna, everyone),
                    entry(13, Kind.EXPENSE, "Taxi", "2026-09-25", 900, anna, everyone),
                )
            groups.append(
                trip,
                entries.map { Event.EntryCreated(eventId(), anna.id, at, it) },
                today,
            )
        }
        return StatisticsViewModel(groups, trip) { today }
    }

    private fun show(withEntries: Boolean = true) {
        val viewModel = viewModel(withEntries)
        compose.setContent {
            NeugesplitterTheme { StatisticsScreen(viewModel, {}, { opened = it }, { settings++ }) }
        }
    }

    private var opened: Chart? = null
    private var settings = 0

    @Test
    fun `the menu leads to the group's settings (ui-design 1)`() {
        show()
        // The menu shows once the statistics are, after the title.
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose
                .onAllNodesWithContentDescription("More options", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("More options", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Settings").performClick()
        assertEquals(1, settings)
    }

    private fun entry(
        number: Int,
        kind: Kind,
        description: String,
        date: String,
        amount: Long,
        payer: Member,
        beneficiaries: Side,
    ) =
        Entry(
            id = EntryId.parse(id(number)),
            kind = kind,
            description = description,
            date = EntryDate.parse(date),
            amount = cents(amount),
            payers = Side.Equal(listOf(payer.id)),
            beneficiaries = beneficiaries,
        )

    private fun awaitText(value: String) {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showChart(chart: Chart) {
        val viewModel = viewModel()
        compose.setContent {
            NeugesplitterTheme {
                ChartScreen(viewModel, chart, {}, onEntries = { l, f -> listed = l to f })
            }
        }
        awaitText(text(chart.title))
    }

    private var listed: Pair<Listing, EntryFilter>? = null

    private fun displayedRange(): String =
        compose
            .onAllNodes(hasText("–", substring = true))
            .fetchSemanticsNodes()
            .single()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    @Test
    fun `statistics lists all charts and net spending opens the spending chart`() {
        show()
        awaitText(text(R.string.chart_balance))

        for (chart in Chart.entries) {
            compose
                .onNode(hasScrollAction())
                .performScrollToNode(hasText(text(chart.title), substring = true))
            compose.onNode(hasText(text(chart.title), substring = true)).assertExists()
        }

        compose
            .onNode(hasScrollAction())
            .performScrollToNode(hasText(text(R.string.chart_spending), substring = true))
        compose
            .onNode(hasText(text(R.string.chart_spending), substring = true) and hasClickAction())
            .performClick()
        assertEquals(Chart.SPENDING, opened)
    }

    @Test
    fun `a group without entries shows the empty statistics message`() {
        show(withEntries = false)

        awaitText(text(R.string.statistics_empty))
        compose.onNodeWithText(text(R.string.statistics_empty)).assertExists()
    }

    @Test
    fun `categories show year span total spending and an earlier date range`() {
        showChart(Chart.CATEGORIES)
        awaitText(text(R.string.span_year))

        compose.onNodeWithText(text(R.string.span_year)).assertExists()
        compose.onNodeWithText(text(R.string.total_spending)).assertExists()
        val before = displayedRange()

        compose.onNodeWithContentDescription(text(R.string.earlier)).performClick()

        compose.waitUntil(5_000) { displayedRange() != before }
        assertNotEquals(before, displayedRange())
    }

    @Test
    fun `balance chart renders member name chips`() {
        showChart(Chart.BALANCE)

        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose
                .onAllNodes(hasText("Anna") and hasClickAction())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNode(hasText("Anna") and hasClickAction()).assertExists()
    }

    @Test
    fun `a category of the categories chart lists its expenses in the period (BA-16)`() {
        showChart(Chart.CATEGORIES)
        awaitText(text(R.string.category_none))

        compose.onNodeWithText(text(R.string.category_none)).performClick()

        val (listing, filter) = checkNotNull(listed)
        assertEquals("$NO_CATEGORY ${text(R.string.category_none)}", listing.title)
        assertEquals(
            EntryFilter(
                kind = Kind.EXPENSE,
                uncategorized = true,
                from = EntryDate.parse("2025-10-08"),
                until = today,
                transfers = false,
            ),
            filter,
        )
        assertEquals(listOf("Taxi", "Groceries"), matching(filter))
    }

    @Test
    fun `an income node of the flows chart lists the income in the period (BA-16)`() {
        showChart(Chart.FLOWS)
        awaitText(text(R.string.kind_income))

        compose.onAllNodesWithText("$NO_CATEGORY ${text(R.string.category_none)}")[0].performClick()

        val (listing, filter) = checkNotNull(listed)
        assertTrue(listing.subtitle.startsWith(text(R.string.kind_income)))
        assertEquals(Kind.INCOME, filter.kind)
        assertEquals(listOf("Refund"), matching(filter))
    }

    @Test
    fun `net spending lists the entries of the period read off (BA-16)`() {
        showChart(Chart.SPENDING)
        awaitText(text(R.string.spending_net_label))

        compose.onNodeWithText(text(R.string.spending_net_label)).performClick()

        val (listing, filter) = checkNotNull(listed)
        assertEquals(text(R.string.chart_spending), listing.title)
        assertEquals(
            EntryFilter(from = EntryDate.parse("2026-10-04"), until = today, transfers = false),
            filter,
        )
    }

    private fun matching(filter: EntryFilter): List<String> {
        val overview = checkNotNull(runBlocking { groups.overview(trip, { today }).first() })
        return filter.matching(overview.entries, overview.scale).map { it.description }
    }
}
