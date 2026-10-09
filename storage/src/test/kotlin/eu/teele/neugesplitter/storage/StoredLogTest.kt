package eu.teele.neugesplitter.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.AT
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.entryId
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.format.json.LogObject
import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.Fetched
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.ObjectNumber.Companion.ROOT
import eu.teele.neugesplitter.sync.Sealing
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import eu.teele.neugesplitter.sync.writer.Writer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class StoredLogTest {

    private val driver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val database = Database(driver)
    private val groups = GroupRepository(driver)
    private val log = groups.local(GROUP, { TODAY }, Dispatchers.Unconfined)

    private fun euros(n: Long) = Amount.ofUnits(n * 100_000_000)

    private fun batch(n: Int) = BatchId.parse("%032x".format(0xba00 + n))

    private fun event(n: Int) = EventId.parse("%032x".format(0x100 + n))

    private val both = listOf(ANNA, BEN)

    /** A 30 € entry Anna paid for both of them. */
    private val base =
        Log()
            .create()
            .add(ANNA, "Anna")
            .add(BEN, "Ben")
            .record(entry(1, euros(30), Side.Equal(listOf(ANNA)), Side.Equal(both)))
            .events()
    private val baseBytes = LogObject(batch(0), null, base).write()

    private fun update(n: Int, by: MemberId, changes: EntryChanges) =
        Event.EntryUpdated(event(n), by, AT, entryId(1), changes)

    private val forty = update(1, ANNA, EntryChanges(amount = Change.To(euros(40))))

    private val absolute =
        update(
            2,
            BEN,
            EntryChanges(
                beneficiaries = Change.To(Side.Absolute(mapOf(ANNA to euros(15), BEN to euros(15))))
            ),
        )

    private fun objectOf(n: Int, vararg events: Event) =
        LogObject(batch(n), null, events.toList()).write()

    private fun fetched(n: Int, bytes: ByteArray) =
        Fetched(ObjectNumber(n), bytes, LogObject.receive(bytes))

    /** The base is object 0 on the backend and here. */
    private suspend fun synced() {
        groups.create(base, TODAY)
        log.batch(batch(0))
        log.written(ROOT, baseBytes)
    }

    private fun objectNumbers() =
        database.eventRowQueries.rows(GROUP.hex).executeAsList().map { it.object_number }

    @Test
    fun `waiting events close into one batch whose id survives a restart (format-spec 2_2)`():
        Unit = runBlocking {
        groups.create(base, TODAY)
        assertNull(log.closed())
        val closed = log.batch(batch(1))!!
        assertEquals(batch(1), closed.id)
        assertEquals(base, closed.events)

        val restarted = GroupRepository(driver).local(GROUP, { TODAY }, Dispatchers.Unconfined)
        assertEquals(batch(1), restarted.closed())
        val again = restarted.batch(batch(2))!!
        assertEquals(batch(1), again.id)
        assertEquals(base, again.events)
    }

    @Test
    fun `events recorded after a batch closes wait for the next one (format-spec 2_2)`(): Unit =
        runBlocking {
            groups.create(base, TODAY)
            log.batch(batch(0))
            groups.append(GROUP, listOf(forty), TODAY)
            assertEquals(base, log.batch(batch(1))!!.events)

            log.written(ROOT, baseBytes)
            val next = log.batch(batch(1))!!
            assertEquals(batch(1), next.id)
            assertEquals(listOf<Event>(forty), next.events)
        }

    @Test
    fun `a written batch's events take its number and its bytes are the head (TR-17)`(): Unit =
        runBlocking {
            synced()
            assertEquals(List(base.size) { 0L }, objectNumbers())
            val head = log.head()!!
            assertEquals(ROOT, head.number)
            assertContentEquals(baseBytes, head.bytes)
            assertNull(log.batch(batch(1)))
        }

    @Test
    fun `received events go ahead of those waiting, in the texts they came in (TR-17b, TR-17c)`():
        Unit = runBlocking {
        synced()
        groups.append(GROUP, listOf(forty), TODAY)
        val renamed =
            """{"id":"${event(3).hex}", "type":"member.renamed","by":"${BEN.hex}",""" +
                """"at":"2026-08-31T18:04:11Z","target":"${BEN.hex}","name":"Benni","mood":1}"""
        val bytes =
            """{"neugesplitter":1,"batch":"${batch(9).hex}","events":[$renamed]}"""
                .encodeToByteArray()

        log.received(listOf(fetched(1, bytes)))

        val rows = database.eventRowQueries.rows(GROUP.hex).executeAsList()
        assertEquals(
            base.map { EventJson.write(it) } + renamed + EventJson.write(forty),
            rows.map { it.json },
        )
        assertEquals(List(base.size) { 0L } + 1L + null, rows.map { it.object_number })
        assertEquals(ObjectNumber(1), log.head()!!.number)
        assertEquals("Benni", groups.load(GROUP)!!.members.getValue(BEN).name)
        assertEquals(listOf<Event>(forty), log.batch(batch(1))!!.events)
    }

    @Test
    fun `what is received is unseen until its line, its entry or all of it is seen (HI-5, HI-6, HI-7)`():
        Unit = runBlocking {
        synced()
        groups.append(GROUP, listOf(forty), TODAY)
        val renamed = Event.MemberRenamed(event(3), BEN, AT, BEN, "Benni")
        val received = base.size

        log.received(listOf(fetched(1, objectOf(9, absolute)), fetched(2, objectOf(10, renamed))))

        assertEquals(setOf(received, received + 1), groups.unseen(GROUP).first())
        assertEquals(true, groups.overview(GROUP, TODAY).first()!!.entries.single().unseen)
        assertEquals(true, groups.list(TODAY).first().single().unseen)

        groups.seen(GROUP, entryId(1))
        assertEquals(setOf(received + 1), groups.unseen(GROUP).first())
        assertEquals(false, groups.overview(GROUP, TODAY).first()!!.entries.single().unseen)

        groups.seen(GROUP, received + 1)
        assertEquals(emptySet(), groups.unseen(GROUP).first())
        assertEquals(false, groups.list(TODAY).first().single().unseen)

        val described = update(5, BEN, EntryChanges(description = Change.To("Dinner")))
        log.received(listOf(fetched(3, objectOf(11, described))))
        assertEquals(setOf(received + 2), groups.unseen(GROUP).first())
        groups.seenAll(GROUP)
        assertEquals(emptySet(), groups.unseen(GROUP).first())
    }

    @Test
    fun `the history a join fetches is seen already (HI-5, BE-6)`(): Unit = runBlocking {
        log.received(listOf(fetched(0, baseBytes)))
        assertEquals(emptySet(), groups.unseen(GROUP).first())

        groups.actAs(GROUP, ANNA)
        log.received(listOf(fetched(1, objectOf(9, absolute))))
        assertEquals(setOf(base.size), groups.unseen(GROUP).first())

        groups.delete(GROUP)
        assertEquals(0, database.unseenQueries.positions(GROUP.hex).executeAsList().size)
    }

    @Test
    fun `a halted group is listed as such (SY-15)`(): Unit = runBlocking {
        synced()
        assertEquals(false, groups.list(TODAY).first().single().halted)
        groups.backends.halted(GROUP, Upload.Halted(ObjectNumber(1), Halt.GAP))
        assertEquals(true, groups.list(TODAY).first().single().halted)
    }

    @Test
    fun `a waiting update that no longer applies is restated, and its batch keeps its id (sync-design 4)`():
        Unit = runBlocking {
        synced()
        groups.append(GROUP, listOf(forty), TODAY)
        log.batch(batch(1))
        val bytes = objectOf(9, absolute)

        log.received(listOf(fetched(1, bytes)))

        val restated =
            forty.copy(
                entry =
                    EntryChanges(
                        amount = Change.To(euros(40)),
                        beneficiaries = Change.To(Side.Equal(both)),
                    )
            )
        val closed = log.batch(batch(2))!!
        assertEquals(batch(1), closed.id)
        assertEquals(listOf<Event>(restated), closed.events)
        assertEquals(base + absolute + restated, groups.events(GROUP))
        val entry = groups.load(GROUP)!!.entries.getValue(entryId(1))
        assertEquals(euros(40), entry.amount)
        assertEquals(Side.Equal(both), entry.beneficiaries)
        assertEquals(euros(40), groups.overview(GROUP, TODAY).first()!!.entries.single().amount)
    }

    @Test
    fun `an object contradicting the log is refused and nothing is kept (format-spec 4_2)`(): Unit =
        runBlocking {
            synced()
            val again = Event.MemberAdded(event(4), BEN, AT, Member(ANNA, "Anna"))

            assertEquals(0, log.received(listOf(fetched(1, objectOf(9, again)))))

            assertEquals(base, groups.events(GROUP))
            assertEquals(ROOT, log.head()!!.number)
        }

    @Test
    fun `objects before a contradicting one are applied, the rest are not (format-spec 4_2)`():
        Unit = runBlocking {
        synced()
        val again = Event.MemberAdded(event(4), BEN, AT, Member(ANNA, "Anna"))
        val described = update(5, BEN, EntryChanges(description = Change.To("Dinner")))
        val objects =
            listOf(
                fetched(1, objectOf(9, absolute)),
                fetched(2, objectOf(10, again)),
                fetched(3, objectOf(11, described)),
            )

        assertEquals(1, log.received(objects))

        assertEquals(base + absolute, groups.events(GROUP))
        assertEquals(List(base.size) { 0L } + 1L, objectNumbers())
        assertEquals(ObjectNumber(1), log.head()!!.number)
    }

    @Test
    fun `objects received together end as when received one by one (TR-17b)`(): Unit = runBlocking {
        val described = update(5, BEN, EntryChanges(description = Change.To("Dinner")))
        val objects =
            listOf(
                fetched(1, objectOf(9, absolute)),
                fetched(2, objectOf(10, described)),
                fetched(3, objectOf(11, update(6, ANNA, EntryChanges(note = Change.To("x"))))),
            )
        val other = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
        val singly = GroupRepository(other)
        val one = singly.local(GROUP, { TODAY }, Dispatchers.Unconfined)
        for ((repository, local) in listOf(groups to log, singly to one)) {
            repository.create(base, TODAY)
            local.batch(batch(0))
            local.written(ROOT, baseBytes)
            repository.append(GROUP, listOf(forty), TODAY)
            local.batch(batch(1))
        }

        assertEquals(3, log.received(objects))
        assertEquals(3, objects.sumOf { one.received(listOf(it)) })

        val rows = { driver: JdbcSqliteDriver ->
            Database(driver).eventRowQueries.rows(GROUP.hex).executeAsList().map {
                Triple(it.local_order, it.json, it.object_number)
            }
        }
        assertEquals(rows(driver), rows(other))
        assertEquals(groups.events(GROUP), singly.events(GROUP))
        assertEquals(groups.overview(GROUP, TODAY).first(), singly.overview(GROUP, TODAY).first())
        assertEquals(ObjectNumber(3), log.head()!!.number)
    }

    @Test
    fun `a writer that loses its number writes the restated update after the winner (sync-design 4)`():
        Unit = runBlocking {
        synced()
        val store = BucketStore()
        store.createIfAbsent(ROOT, baseBytes)
        store.createIfAbsent(ObjectNumber(1), objectOf(9, absolute))
        groups.append(GROUP, listOf(forty), TODAY)

        val written = Writer(store, log, Sealing.Plain) { batch(1) }.write()

        assertEquals(Upload.Written(ObjectNumber(2)), written)
        val carried = LogObject.read(store.get(ObjectNumber(2))!!)
        assertEquals(batch(1), carried.batch)
        val update = carried.events.single() as Event.EntryUpdated
        assertEquals(forty.id, update.id)
        assertEquals(Change.To(Side.Equal(both)), update.entry.beneficiaries)
        assertEquals(List(base.size) { 0L } + 1L + 2L, objectNumbers())
    }
}
