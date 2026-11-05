package eu.teele.neugesplitter.ui.category

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.category.Category
import eu.teele.neugesplitter.domain.category.defaultCategories
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class CategorySettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var identifiers = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val trip = GroupId.parse(id(9))
    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun show() {
        groups.create(
            Group.create(trip, "Trip", null, null, null, listOf(anna), at, ::eventId),
            today,
        )
        val housing = defaultCategories().single { it.builtin == BuiltInCategory.HOUSING }
        val books = Category(CategoryId.parse(id(20)), name = "Books", emoji = "📚")
        groups.append(
            trip,
            listOf(
                Event.CategoryCreated(eventId(), anna.id, at, books),
                Event.CategoryDeleted(eventId(), anna.id, at, housing.id),
            ),
            today,
        )
        val viewModel =
            CategorySettingsViewModel(
                groups,
                trip,
                { id(0x300 + identifiers++) },
                { today },
                { at },
            )
        compose.setContent { NeugesplitterTheme { CategorySettingsScreen(viewModel, onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Books").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun top(text: String) =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `categories are alphabetized and deleted categories can be shown and hidden`() {
        show()

        assertTrue(top("Books") < top("Dining & cafes"))
        compose.onNodeWithText("Housing").assertDoesNotExist()

        compose.onNodeWithContentDescription("Show deleted categories").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Housing").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(top("Health") < top("Housing"))
        compose.onNodeWithContentDescription("Hide deleted categories").performClick()
        compose.onNodeWithText("Housing").assertDoesNotExist()
    }
}
