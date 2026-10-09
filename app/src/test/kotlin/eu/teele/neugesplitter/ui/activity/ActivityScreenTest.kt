package eu.teele.neugesplitter.ui.activity

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import eu.teele.neugesplitter.storage.settings.Failure
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import eu.teele.neugesplitter.ui.receive
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class ActivityScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private val zone = TimeZone.getDefault()
    private val opened = mutableListOf<EntryId>()
    private val histories = mutableListOf<EntryId>()
    private val synced = mutableListOf<GroupId>()
    private var shares = 0
    private var problems = 0

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))
    private val dinnerId = EntryId.parse(id(10))
    private val taxiId = EntryId.parse(id(11))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restoreZone() = TimeZone.setDefault(zone)

    private fun eventId() = EventId.parse(id(0x100 + events++))

    private fun entry(id: EntryId, description: String) =
        Entry(
            id = id,
            kind = Kind.EXPENSE,
            description = description,
            date = today,
            amount = Amount.ofUnits(30_000_000L),
            payers = Side.Equal(listOf(anna.id)),
            beneficiaries = Side.Equal(listOf(anna.id, ben.id)),
        )

    private fun hours(n: Long) = at.plusSeconds(n * 3600)

    /** Anna's group "Trip", where Ben added a dinner after an hour, read two hours in. */
    private fun show(before: () -> Unit = {}) {
        groups.create(
            Group.create(trip, "Trip", null, "🏖", null, listOf(anna, ben), at, ::eventId),
            today,
        )
        groups.append(
            trip,
            listOf(Event.EntryCreated(eventId(), ben.id, hours(1), entry(dinnerId, "Dinner"))),
            today,
        )
        before()
        val viewModel = ActivityViewModel(groups, trip, { hours(2) }, { synced += it })
        compose.setContent {
            NeugesplitterTheme {
                ActivityScreen(
                    viewModel,
                    onBack = {},
                    onOpen = { opened += it },
                    onHistory = { histories += it },
                    onShare = { shares++ },
                    onSyncProblem = { problems++ },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("🏖 Trip").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun share() {
        groups.backends.connect(
            trip,
            BackendSettings("https://s3.example", "splits", "", "auto", false, "AK"),
        )
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun unseen() =
        compose.onAllNodesWithContentDescription("New").fetchSemanticsNodes().size

    private fun top(text: String) =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `every change is listed newest first, with who made it and when (HI-2)`() {
        show()
        val created = "Anna created the group · 2 hours ago"
        val dinner = "Ben added 'Dinner' · 1 hour ago"
        compose.onNodeWithText(created).assertExists()
        compose.onNodeWithText("Anna added Ben · 2 hours ago").assertExists()
        compose.onNodeWithText(dinner).assertExists()
        assertTrue(top(dinner) < top(created))
    }

    @Test
    fun `a change to an entry opens it, or its history once it is deleted (HI-2, HI-3)`() {
        show {
            groups.append(
                trip,
                listOf(
                    Event.EntryCreated(eventId(), anna.id, hours(1), entry(taxiId, "Taxi")),
                    Event.EntryDeleted(eventId(), anna.id, hours(1), taxiId),
                ),
                today,
            )
        }
        compose.onNodeWithText("Ben added 'Dinner' · 1 hour ago").performClick()
        assertEquals(listOf(dinnerId), opened)
        compose.onNodeWithText("Anna deleted 'Taxi' · 1 hour ago").performClick()
        compose.onNodeWithText("Anna added 'Taxi' · 1 hour ago").performClick()
        assertEquals(listOf(taxiId, taxiId), histories)
        compose.onNodeWithText("Anna created the group · 2 hours ago").performClick()
        assertEquals(listOf(dinnerId), opened)
    }

    @Test
    fun `changes from elsewhere stand out until their line is tapped (HI-5)`() {
        show {
            groups.receive(
                trip,
                today,
                1,
                Event.MemberRenamed(eventId(), ben.id, hours(1), ben.id, "Benni"),
                Event.EntryCreated(eventId(), ben.id, hours(1), entry(taxiId, "Taxi")),
            )
        }
        compose.waitUntil(5_000) { unseen() == 2 }
        compose.onNodeWithText("Benni renamed Ben to Benni · 1 hour ago").performClick()
        compose.waitUntil(5_000) { unseen() == 1 }
        compose.onNodeWithText("Benni added 'Taxi' · 1 hour ago").performClick()
        assertEquals(listOf(taxiId), opened)
        compose.waitUntil(5_000) { unseen() == 0 }
        compose.onNodeWithText("Benni added 'Dinner' · 1 hour ago").assertExists()
    }

    @Test
    fun `mark all as read clears every highlight at once (HI-7)`() {
        show {
            groups.receive(
                trip,
                today,
                1,
                Event.MemberRenamed(eventId(), ben.id, hours(1), ben.id, "Benni"),
                Event.EntryCreated(eventId(), ben.id, hours(1), entry(taxiId, "Taxi")),
            )
        }
        compose.waitUntil(5_000) { unseen() == 2 }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Mark all as read").performClick()
        compose.waitUntil(5_000) { unseen() == 0 }
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a group kept only here offers to share it and shows no sync state (SY-6)`() {
        show()
        compose.onNodeWithText("Only on this device").assertExists()
        compose.onNodeWithText("Set up shared storage").performClick()
        assertEquals(1, shares)
        assertEquals(0, count("Sync now"))
        compose.onNodeWithContentDescription("In sync").assertDoesNotExist()
        compose.onNodeWithContentDescription("Changes waiting").assertDoesNotExist()
    }

    @Test
    fun `a shared group shows what is waiting and syncs on demand (SY-6, SY-9)`() {
        show(::share)
        compose.onNodeWithText("Not synced yet").assertExists()
        compose.onNodeWithText("4 changes waiting").assertExists()
        compose.onNodeWithContentDescription("Changes waiting").assertExists()
        compose.onNodeWithText("Sync now").performClick()
        assertEquals(listOf(trip), synced)
    }

    @Test
    fun `a failed pass shows why next to the last success (SY-6, TR-12, BE-7)`() {
        show {
            share()
            groups.backends.succeeded(trip, hours(1))
            groups.backends.failed(trip, Failure.UNREACHABLE)
        }
        compose.onNodeWithText("Last successful: 1 hour ago").assertExists()
        compose.onNodeWithText("Not reachable").assertExists()
        compose.onNodeWithContentDescription("Sync problem").assertExists()
        compose.onNodeWithText("Sync now").assertExists()
    }

    @Test
    fun `a halted group says sync stopped, leads to why, and offers no manual sync (SY-15)`() {
        show {
            share()
            groups.backends.halted(trip, Upload.Halted(ObjectNumber(3), Halt.GAP))
        }
        compose.onNodeWithContentDescription("Sync problem").assertExists()
        assertEquals(0, count("Sync now"))
        compose.onNodeWithText("Sync stopped").performClick()
        assertEquals(1, problems)
    }

    @Test
    fun `each section folds away and back (ui-design 4)`() {
        show()
        val dinner = "Ben added 'Dinner' · 1 hour ago"
        compose.onAllNodesWithContentDescription("Collapse")[0].performClick()
        assertEquals(0, count(dinner))
        compose.onNodeWithText("Only on this device").assertExists()
        compose.onAllNodesWithContentDescription("Collapse")[0].performClick()
        assertEquals(0, count("Only on this device"))
        compose.onAllNodesWithContentDescription("Expand")[0].performClick()
        assertEquals(1, count(dinner))
    }
}
