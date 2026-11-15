package eu.teele.neugesplitter.ui.tidy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.category.BuiltInCategory
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class TidyScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var identifiers = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))
    private val october = EntryId.parse(id(30))
    private val september = EntryId.parse(id(31))
    private val pizzas = listOf(EntryId.parse(id(32)), EntryId.parse(id(33)))
    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun expense(id: EntryId, description: String, date: String, payer: Member) =
        Entry(
            id,
            Kind.EXPENSE,
            description,
            EntryDate.parse(date),
            Amount.ofUnits(100_00000000),
            Side.Equal(listOf(payer.id)),
            Side.Equal(listOf(anna.id, ben.id)),
        )

    private fun entry(id: EntryId) = groups.load(trip)!!.entries.getValue(id)

    private fun show(shared: Boolean = false, more: List<Entry> = emptyList()) {
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna, ben), at, ::eventId),
            today,
        )
        val entries =
            listOf(
                expense(october, "Gehalt", "2026-10-01", ben),
                expense(september, "Gehalt", "2026-09-01", ben),
                expense(pizzas[0], "Pizza", "2026-09-02", anna),
                expense(pizzas[1], "Pizza!", "2026-09-03", anna),
            ) + more
        groups.append(trip, entries.map { Event.EntryCreated(eventId(), anna.id, at, it) }, today)
        if (shared) {
            groups.backends.connect(
                trip,
                BackendSettings("https://s3.example", "bucket", trip.hex, "auto", true, "key"),
            )
        }
        val viewModel =
            TidyViewModel(
                groups,
                trip,
                { id(0x300 + identifiers++) },
                { today },
                { at },
                rank = { listOf(BuiltInCategory.DINING_AND_CAFES, BuiltInCategory.GROCERIES) },
                guess = {
                    when {
                        "Pizza" in it -> BuiltInCategory.DINING_AND_CAFES
                        it == "Taxi" -> BuiltInCategory.TRANSPORTATION
                        else -> null
                    }
                },
            )
        compose.setContent { NeugesplitterTheme { TidyScreen(viewModel, onBack = {}) } }
        waitFor("Make income")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `ticked expenses become incomes with the same balances and unticked ones are not asked again`() {
        show()
        val before = entry(october).contributions()

        compose.onNodeWithText("2 entries").assertExists()
        compose.onNodeWithText("Sep 1, 2026", substring = true).performClick()
        compose.onNodeWithText("Make income").performClick()

        compose.waitUntil(5_000) { entry(october).kind == Kind.INCOME }
        assertEquals(before, entry(october).contributions())
        assertEquals(Side.Equal(listOf(anna.id)), entry(october).payers)
        assertEquals(Kind.EXPENSE, entry(september).kind)
        waitFor("No entries left that look like an income.")
    }

    @Test
    fun `entries without a category take the suggested one`() {
        show()

        compose.onNodeWithText("Categories (4)").performClick()
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithText("Dining & cafes", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onAllNodesWithText("Apply").onFirst().performClick()

        val dining = BuiltInCategory.DINING_AND_CAFES.id
        compose.waitUntil(5_000) { pizzas.all { entry(it).categoryId == dining } }
        assertNull(entry(october).categoryId)
    }

    private fun toggles(text: String) =
        compose.onAllNodes(isToggleable() and hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `only a batch of several entries offers to leave some out (TI-2, TI-3)`() {
        show(more = listOf(expense(EntryId.parse(id(40)), "Taxi", "2026-09-04", anna)))

        compose.onNodeWithText("Categories (5)").performClick()
        waitFor("Taxi")
        assertEquals(2, toggles("Pizza") + toggles("Pizza!"))
        assertEquals(0, toggles("Taxi"))
    }

    @Test
    fun `a long batch handled leaves the next one in view from its top (TI-3)`() {
        val more =
            (0 until 30).flatMap { n ->
                listOf(
                    expense(EntryId.parse(id(0x400 + n)), "Pizza", "2026-08-01", anna),
                    expense(EntryId.parse(id(0x500 + n)), "Taxi", "2026-08-01", anna),
                )
            }
        show(more = more)

        compose.onNodeWithText("Categories (64)").performClick()
        waitFor("32 entries")
        compose.onAllNodesWithText("Apply").onFirst().performScrollTo().performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("32 entries").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText("30 entries").assertIsDisplayed()
    }

    @Test
    fun `a shared group shows the changes as its activity will before writing them`() {
        show(shared = true)

        compose.onNodeWithText("Make income").performClick()
        waitFor("Apply 2 changes")
        compose.onAllNodesWithText("Anna edited 'Gehalt'").fetchSemanticsNodes().let {
            assertEquals(2, it.size)
        }
        compose.onNodeWithText("Cancel").performClick()
        waitFor("Make income")
        assertEquals(Kind.EXPENSE, entry(october).kind)

        compose.onNodeWithText("Make income").performClick()
        waitFor("Apply 2 changes")
        compose.onNodeWithText("Apply 2 changes").performClick()
        compose.waitUntil(5_000) {
            entry(october).kind == Kind.INCOME && entry(september).kind == Kind.INCOME
        }
    }
}
