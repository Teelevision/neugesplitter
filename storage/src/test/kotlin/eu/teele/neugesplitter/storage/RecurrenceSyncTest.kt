package eu.teele.neugesplitter.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.AT
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.RENT
import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.date
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Occurrence
import eu.teele.neugesplitter.domain.segment
import eu.teele.neugesplitter.domain.template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.GroupSync
import eu.teele.neugesplitter.sync.Sealing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Two installations of one group, sharing a bucket, acting on one series (RE-8). */
class RecurrenceSyncTest {

    private val store = BucketStore()

    private fun euros(n: Long) = Amount.ofUnits(n * 100_000_000)

    private fun monthly(amount: Long) =
        segment(
            Interval.MonthlyOn(setOf(1)),
            template(euros(amount), Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, BEN))),
            Mode.SUGGESTED,
        )

    /** Rent of 800 €, suggested on the first of every month since June. */
    private val base =
        Log()
            .create()
            .add(ANNA, "Anna")
            .add(BEN, "Ben")
            .start(RENT, "2026-06-01", monthly(800))
            .events()

    private inner class Installation {
        val groups =
            GroupRepository(
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
            )
        var today = TODAY
        private val sync =
            GroupSync(store, groups.local(GROUP, { today }, Dispatchers.Unconfined), Sealing.Plain)

        fun pass() = runBlocking { sync.pass() }

        fun write(vararg events: Event) = groups.append(GROUP, events.toList(), today)

        fun occurrences(): List<Occurrence> = groups.load(GROUP)!!.occurrences(today)

        fun dues() = occurrences().map { it.due.toString() }

        fun pending() = occurrences().filter { it.pending }.map { it.due.toString() }
    }

    private val anna = Installation().apply { groups.create(base, TODAY) }
    private val ben = Installation()

    private var serial = 0

    private fun id() = EventId.parse("%032x".format(0xbe00 + ++serial))

    private fun confirm(due: String) =
        Event.OccurrenceConfirmed(EventId.occurrence(RENT, date(due)), BEN, AT, RENT, date(due))

    private fun decline(due: String) =
        Event.OccurrenceDeleted(EventId.occurrence(RENT, date(due)), BEN, AT, RENT, date(due))

    /** Both installations hold all of the log, in one order. */
    private fun synced() {
        anna.pass()
        ben.pass()
        anna.pass()
        assertEquals(anna.groups.events(GROUP), ben.groups.events(GROUP))
    }

    private fun both(check: (Installation) -> Unit) {
        check(anna)
        check(ben)
    }

    @Test
    fun `both installations produce the same occurrences, under the same ids (RE-8)`() {
        synced()
        both { assertEquals(listOf("2026-06-01", "2026-07-01", "2026-08-01"), it.pending()) }
        assertEquals(anna.occurrences(), ben.occurrences())
        val june = EntryId.occurrence(RENT, date("2026-06-01"))
        assertEquals(june, ben.occurrences().first().entry.id)
    }

    @Test
    fun `an open overview takes in what fell due once the date has moved on (RE-14)`() {
        synced()
        anna.write(Log().confirm(RENT, "2026-08-01").events().single())
        val overview = anna.groups.overview(GROUP, { anna.today }, Dispatchers.Unconfined)
        val pending = anna.groups.pending(GROUP, { anna.today }, Dispatchers.Unconfined)
        assertEquals(1, runBlocking { overview.first() }!!.entries.size)
        assertEquals(2, runBlocking { pending.first() })

        anna.today = date("2026-09-01")
        assertEquals(3, runBlocking { pending.first() })
        anna.write(Log().confirm(RENT, "2026-09-01").events().single())
        val dates = runBlocking { overview.first() }!!.entries.map { it.date.toString() }
        assertEquals(listOf("2026-09-01", "2026-08-01"), dates)
    }

    @Test
    fun `one confirmed and the other declined meanwhile, the later answer stands on both (RE-4, RE-5)`() {
        synced()
        anna.write(Log().confirm(RENT, "2026-07-01").events().single())
        ben.write(decline("2026-07-01"))

        synced()

        both { assertEquals(listOf("2026-06-01", "2026-08-01"), it.dues()) }
        assertEquals(anna.occurrences(), ben.occurrences())
    }

    @Test
    fun `both confirming one occurrence meanwhile count it once (RE-4)`() {
        synced()
        anna.write(Log().confirm(RENT, "2026-07-01").events().single())
        ben.write(confirm("2026-07-01"))

        synced()

        both {
            assertEquals(listOf("2026-06-01", "2026-08-01"), it.pending())
            val july = it.occurrences().single { o -> o.due == date("2026-07-01") }
            assertEquals(euros(800), july.entry.amount)
        }
        assertEquals(anna.occurrences(), ben.occurrences())
    }

    @Test
    fun `an amendment and a stop written meanwhile both hold, in either order (RE-6, RE-7)`() {
        synced()
        anna.write(Log().amend(RENT, "2026-08-01", monthly(850)).events().single())
        ben.write(Event.SeriesStopped(id(), BEN, AT, RENT, date("2026-07-15"), null))

        synced()

        both { assertEquals(listOf("2026-06-01", "2026-07-01"), it.dues()) }

        // Taken up again in October, the series goes on at the amended rent.
        ben.write(Event.SeriesContinued(id(), BEN, AT, RENT, date("2026-10-01"), null))
        synced()
        both {
            it.today = date("2026-10-01")
            val october = it.occurrences().last()
            assertEquals(date("2026-10-01"), october.due)
            assertEquals(euros(850), october.entry.amount)
            assertEquals(listOf("2026-06-01", "2026-07-01", "2026-10-01"), it.dues())
        }
    }

    @Test
    fun `an occurrence confirmed while the other stopped the series outlives the stop (RE-7)`() {
        synced()
        anna.write(Log().confirm(RENT, "2026-08-01").events().single())
        ben.write(Event.SeriesStopped(id(), BEN, AT, RENT, date("2026-07-15"), null))

        synced()

        both {
            assertEquals(listOf("2026-06-01", "2026-07-01", "2026-08-01"), it.dues())
            assertEquals(listOf("2026-06-01", "2026-07-01"), it.pending())
        }
        assertEquals(anna.occurrences(), ben.occurrences())
    }
}
