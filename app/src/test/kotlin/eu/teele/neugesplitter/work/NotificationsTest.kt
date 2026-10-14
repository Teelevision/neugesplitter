package eu.teele.neugesplitter.work

import android.app.Notification
import android.app.NotificationManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.MainActivity
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
import eu.teele.neugesplitter.ui.receive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class NotificationsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var events = 0
    private var shown = false

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val trip = GroupId.parse(id(9))

    private val groups =
        GroupRepository(AndroidSqliteDriver(Database.Schema, context, null)).also {
            it.create(
                Group.create(trip, "Trip", null, "🏖", null, listOf(anna, ben), at, ::eventId),
                today,
            )
        }

    private val notifications = Notifications(context, groups) { shown }

    private val posted
        get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun eventId() = EventId.parse(id(0x100 + events++))

    /** Ben's expenses [descriptions], received as object [n] from his installation. */
    private fun receive(n: Int, vararg descriptions: String) {
        val added =
            descriptions.map {
                val entry =
                    Entry(
                        id = EntryId.parse(id(0x200 + events)),
                        kind = Kind.EXPENSE,
                        description = it,
                        date = today,
                        amount = Amount.ofUnits(30_000_000L),
                        payers = Side.Equal(listOf(ben.id)),
                        beneficiaries = Side.Equal(listOf(anna.id, ben.id)),
                    )
                Event.EntryCreated(eventId(), ben.id, at, entry)
            }
        groups.receive(trip, today, n, *added.toTypedArray())
    }

    private fun received() = runBlocking { notifications.received(trip) }

    @Test
    fun `changes others made, received in the background, are told about, newest first (SY-10)`() {
        receive(1, "Dinner", "Taxi")
        received()
        val notification = posted.single()
        val extras = notification.extras
        assertEquals("🏖 Trip", extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Ben added 'Taxi'", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(
            listOf("Ben added 'Taxi'", "Ben added 'Dinner'"),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() },
        )
        assertEquals(2, notification.number)
        val tapped = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, tapped.component!!.className)
        assertEquals(trip.hex, tapped.getStringExtra(MainActivity.GROUP))
    }

    @Test
    fun `a notification lists five changes and counts the rest (SY-10)`() {
        receive(1, "One", "Two", "Three", "Four", "Five", "Six", "Seven")
        received()
        val extras = posted.single().extras
        assertEquals(5, extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size)
        assertEquals("+2 more", extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
    }

    @Test
    fun `each change is told about once, and the next one tells what is still unseen (SY-10)`() {
        receive(1, "Dinner")
        received()
        notifications.clear()
        received()
        assertTrue(posted.isEmpty())

        receive(2, "Taxi")
        received()
        assertEquals(2, posted.single().number)
    }

    @Test
    fun `nothing is told while the app is on screen, nor once it is left (SY-10, HI-5)`() {
        shown = true
        receive(1, "Dinner")
        received()
        assertTrue(posted.isEmpty())
        shown = false
        received()
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `a group with nothing unseen tells nothing (SY-10)`() {
        receive(1, "Dinner")
        groups.seenAll(trip)
        received()
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `forgetting a group takes back its notification and what was told (GR-11)`() {
        receive(1, "Dinner")
        received()
        notifications.forget(trip)
        assertTrue(posted.isEmpty())
        received()
        assertEquals(1, posted.size)
    }
}
