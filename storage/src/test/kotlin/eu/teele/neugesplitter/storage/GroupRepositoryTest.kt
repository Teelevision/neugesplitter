package eu.teele.neugesplitter.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.RENT
import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.date
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.entryId
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.segment
import eu.teele.neugesplitter.domain.template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.storage.projection.Projection
import eu.teele.neugesplitter.storage.settings.BackendSettings
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking

class GroupRepositoryTest {

    private val driver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val database = Database(driver)
    private val groups = GroupRepository(driver)

    private val trip = GroupId.parse("%032x".format(0xc0a2))
    private val cleaning = SeriesId.parse("%032x".format(0x5e2))
    private val gym = SeriesId.parse("%032x".format(0x5e3))

    private fun euros(n: Long) = Amount.ofUnits(n * 100_000_000)

    private fun equal(members: List<MemberId>) = Side.Equal(members)

    /** A household that has used every kind of event at least once. */
    private val household =
        Log()
            .create("WG")
            .add(ANNA, "Anna")
            .add(BEN, "Ben")
            .add(CLARA, "Clara")
            .update(
                GroupChanges(
                    colour = Change.To(Colour.parse("#3366cc")),
                    defaultParticipants = Change.To(listOf(ANNA, BEN)),
                )
            )
            .record(entry(1, euros(10), equal(listOf(ANNA)), equal(listOf(ANNA, BEN, CLARA))))
            .record(
                entry(
                    2,
                    euros(90),
                    equal(listOf(BEN)),
                    Side.Shares(mapOf(ANNA to 2L, BEN to 1L)),
                    kind = Kind.INCOME,
                )
            )
            .record(
                entry(
                    3,
                    euros(50),
                    Side.Absolute(mapOf(CLARA to euros(50))),
                    Side.Adjustments(mapOf(ANNA to euros(5), CLARA to euros(0))),
                )
            )
            .update(entryId(1), EntryChanges(description = Change.To("Groceries")))
            .delete(entryId(3))
            .rename(BEN, "Benedikt")
            .start(RENT, "2026-06-01", segment(Interval.MonthlyOn(setOf(1)), rent(900)))
            .confirm(RENT, "2026-06-01")
            .decline(RENT, "2026-07-01")
            .amend(RENT, "2026-08-01", "2026-08-03", rent(950))
            .start(cleaning, "2026-08-20", segment(Interval.EveryDays(3), rent(20), Mode.SUGGESTED))
            .end(cleaning, "2026-08-28")
            .start(gym, "2026-08-01", segment(Interval.EveryDays(7), rent(15)))
            .delete(gym)
            .remove(CLARA)
            .events()

    private val weekend =
        Log(BEN, trip)
            .create("Wochenende")
            .add(BEN, "Ben")
            .add(ANNA, "Anna")
            .record(entry(1, euros(120), equal(listOf(BEN)), equal(listOf(ANNA, BEN))))
            .events()

    private fun rent(euros: Long) =
        template(euros(euros), equal(listOf(ANNA)), equal(listOf(ANNA, BEN)))

    private fun appendedOneByOne(group: GroupId, events: List<Event>) {
        for (event in events) groups.append(group, listOf(event), TODAY)
    }

    private fun connected(group: GroupId) {
        groups.backends.connect(
            group,
            BackendSettings("https://s3.example", "bucket", group.hex, "auto", true, "key"),
        )
        groups.backends.succeeded(group, Instant.EPOCH)
    }

    private fun rows(sql: String): List<String> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    val rows = mutableListOf<String>()
                    while (cursor.next().value) rows += cursor.getString(0) ?: "NULL"
                    QueryResult.Value(rows)
                },
                0,
            )
            .value

    private fun tables(): List<String> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")

    private fun columns(table: String): List<String> =
        rows("SELECT name FROM pragma_table_info('$table')")

    /** Every row of [table] as text, in a stable order. */
    private fun dump(table: String): List<String> {
        val row = columns(table).joinToString(" || '|' || ") { "quote($it)" }
        return rows("SELECT $row FROM $table ORDER BY 1")
    }

    /** Every row of every derived table. */
    private fun projection(): Map<String, List<String>> = Projection.TABLES.associateWith(::dump)

    @Test
    fun `an appended event is kept as the text it was written as (TR-17b)`() {
        appendedOneByOne(GROUP, household)
        groups.append(trip, weekend, TODAY)

        assertEquals(
            household.map(EventJson::write),
            database.eventRowQueries.inGroup(GROUP.hex).executeAsList(),
        )
        assertEquals(
            weekend.map(EventJson::write),
            database.eventRowQueries.inGroup(trip.hex).executeAsList(),
        )
    }

    @Test
    fun `an event's text is never rewritten, not even what this version ignores (TR-17c)`() {
        val written = EventJson.write(household.first())
        val received = "{ \"later\" : [1, \"zwei\", {}],\n" + written.drop(1)
        database.eventRowQueries.append(GROUP.hex, received)

        groups.append(GROUP, household.drop(1), TODAY)
        groups.rebuild(TODAY)

        assertEquals(received, database.eventRowQueries.inGroup(GROUP.hex).executeAsList().first())
        assertEquals("WG", groups.load(GROUP)?.name)
    }

    @Test
    fun `an append that fails keeps neither its events nor their projection (TR-7, TR-17b)`() {
        groups.append(GROUP, household.take(4), TODAY)
        val events = rows("SELECT json FROM event_row")
        val projected = projection()

        val sound = entry(1, euros(10), equal(listOf(ANNA)), equal(listOf(ANNA, BEN)))
        // Every reader refuses a string over 64 KiB, so the second cannot be written at all.
        val oversized =
            entry(
                2,
                euros(10),
                equal(listOf(ANNA)),
                equal(listOf(BEN)),
                description = "x".repeat(70_000),
            )
        assertFailsWith<Malformed> {
            groups.append(GROUP, Log().record(sound).record(oversized).events(), TODAY)
        }
        // A member added twice contradicts the state before it.
        assertFailsWith<Malformed> {
            groups.append(GROUP, Log().record(sound).add(ANNA, "Anna").events(), TODAY)
        }

        assertEquals(events, rows("SELECT json FROM event_row"))
        assertEquals(projected, projection())
    }

    @Test
    fun `the projection kept up to date is the one a rebuild from the log produces (TR-18)`() {
        // Two groups written in turn, each in its own order.
        val interleaved =
            household.indices.flatMap { i ->
                listOfNotNull(GROUP to household[i], weekend.getOrNull(i)?.let { trip to it })
            }
        for ((group, event) in interleaved) {
            groups.append(group, listOf(event), TODAY)
            val kept = projection()

            groups.rebuild(TODAY)

            assertEquals(kept, projection(), "after ${event.type} ${event.id}")
        }
    }

    @Test
    fun `a projection dropped or contradicting the log is replaced by one rebuilt from it (TR-18)`() {
        appendedOneByOne(GROUP, household)
        groups.append(trip, weekend, TODAY)
        val kept = projection()

        driver.execute(null, "UPDATE entry SET amount = 1 WHERE group_id = '${GROUP.hex}'", 0)
        driver.execute(null, "INSERT INTO member VALUES ('${GROUP.hex}', 'stray', 'Stray', 0)", 0)
        for (table in listOf("side", "occurrence", "balance")) {
            driver.execute(null, "DROP TABLE $table", 0)
        }

        groups.rebuild(TODAY)

        assertEquals(kept, projection())
    }

    @Test
    fun `a projection built by another version is rebuilt before anything reads it (TR-18b)`() {
        appendedOneByOne(GROUP, household)
        groups.refresh(TODAY)
        val kept = projection()

        database.projectionQueries.setVersion(Projection.VERSION - 1)
        driver.execute(null, "DELETE FROM balance", 0)
        groups.refresh(TODAY)

        assertEquals(kept, projection())
        assertEquals(Projection.VERSION, database.projectionQueries.version().executeAsOne())
    }

    @Test
    fun `occurrences and balances move on with the local date (RE-14)`() {
        appendedOneByOne(GROUP, household)
        groups.refresh(TODAY)
        val due = "SELECT due FROM occurrence WHERE series = '${RENT.hex}' ORDER BY due"
        assertEquals(listOf("2026-06-01", "2026-08-01"), rows(due))

        val later = date("2026-09-01")
        groups.refresh(later)

        assertEquals(listOf("2026-06-01", "2026-08-01", "2026-09-01"), rows(due))
        assertEquals(listOf(later.toString()), rows("SELECT projected_on FROM group_row"))
        val moved = projection()
        groups.rebuild(later)
        assertEquals(moved, projection())
    }

    @Test
    fun `a new group acts as the member who created it, and is created once (ID-2, ID-3)`() {
        groups.create(weekend, TODAY)
        assertEquals(BEN, groups.actingAs(trip))
        val events = rows("SELECT json FROM event_row")

        assertFailsWith<Malformed> { groups.create(weekend.take(3), TODAY) }
        assertFailsWith<IllegalArgumentException> { groups.create(household.drop(1), TODAY) }

        assertEquals(events, rows("SELECT json FROM event_row"))
    }

    @Test
    fun `a group held without a member to act as is unclaimed, whatever part of it is held (BE-6)`() {
        val rooted = GroupId.parse("%032x".format(0xc0a3))
        val located = GroupId.parse("%032x".format(0xc0a4))
        groups.create(weekend, TODAY)
        groups.append(GROUP, household, TODAY)
        database.logObjectQueries.retain(rooted.hex, 0, null, byteArrayOf(1))
        connected(located)

        assertEquals(setOf(GROUP, rooted, located), groups.unclaimed().toSet())
        groups.actAs(GROUP, ANNA)
        assertEquals(setOf(rooted, located), groups.unclaimed().toSet())
    }

    @Test
    fun `the group list shows each group with the balance of the member acted as, and no group being joined (GR-1, BA-7, BE-6)`() {
        groups.append(GROUP, household, TODAY)
        groups.actAs(GROUP, ANNA)
        groups.append(trip, weekend.take(3), TODAY)
        val group = Group.replay(household)
        val counted = group.entries.size + group.occurrences(TODAY).count { !it.pending }

        assertEquals(
            listOf(
                ListedGroup(
                    id = GROUP,
                    name = "WG",
                    emoji = null,
                    colour = Colour.parse("#3366cc"),
                    scale = Scale.DEFAULT,
                    entries = counted.toLong(),
                    own = group.settlement(TODAY).getValue(ANNA),
                    unseen = false,
                    sync = null,
                )
            ),
            runBlocking { groups.list(TODAY).first() },
        )

        groups.actAs(GROUP, BEN)
        groups.actAs(trip, BEN)

        val listed = runBlocking { groups.list(TODAY).first() }
        assertEquals(group.settlement(TODAY).getValue(BEN), listed.first().own)
        assertEquals(listOf(GROUP, trip), listed.map { it.id })
    }

    @Test
    fun `the group list follows every write (GR-1, GR-11)`() {
        val seen = mutableListOf<List<String>>()
        runBlocking {
            groups.list(TODAY, Dispatchers.Unconfined).take(3).collect { listed ->
                seen += listed.map { it.name }
                when (seen.size) {
                    1 -> groups.create(weekend, TODAY)
                    2 -> groups.delete(trip)
                }
            }
        }
        assertEquals(listOf(emptyList(), listOf("Wochenende"), emptyList()), seen)
    }

    @Test
    fun `the overview lists what counts, newest first, with the part acted as (BA-7, HI-4, RE-9)`() {
        appendedOneByOne(GROUP, household)
        groups.actAs(GROUP, ANNA)
        val group = Group.replay(household)
        val groceries = group.entries.getValue(entryId(1))

        val overview = assertNotNull(runBlocking { groups.overview(GROUP, TODAY).first() })

        assertEquals("WG", overview.name)
        assertEquals(Colour.parse("#3366cc"), overview.colour)
        assertEquals(group.settlement(TODAY).getValue(ANNA), overview.own)
        assertEquals(
            listOf(
                ListedEntry(
                    entryId(2),
                    Kind.INCOME,
                    "Entry 2",
                    TODAY,
                    euros(90),
                    payers = listOf("Benedikt"),
                    own = euros(60),
                    repeats = false,
                    edited = false,
                    unseen = false,
                ),
                ListedEntry(
                    entryId(1),
                    Kind.EXPENSE,
                    "Groceries",
                    TODAY,
                    euros(10),
                    payers = listOf("Anna"),
                    own = groceries.beneficiaries.parts(groceries.amount).getValue(ANNA),
                    repeats = false,
                    edited = true,
                    unseen = false,
                ),
            ),
            overview.entries.take(2),
        )
        // The amended August rent on the day it was moved to, then June's; July's was refused.
        assertEquals(
            listOf(date("2026-08-03") to euros(950), date("2026-06-01") to euros(900)),
            overview.entries.drop(2).map { it.date to it.amount },
        )
        assertTrue(
            overview.entries.drop(2).all {
                it.repeats && it.own == Amount.ofUnits(it.amount.units / 2)
            }
        )
        assertEquals(euros(10 + 950 + 900), overview.total)
    }

    @Test
    fun `the overview names every payer, follows every write and ends with its group (GR-11)`() {
        groups.create(weekend, TODAY)
        val paidByTwo =
            Log(BEN, trip)
                .create("Wochenende")
                .add(BEN, "Ben")
                .add(ANNA, "Anna")
                .record(entry(1, euros(120), equal(listOf(BEN)), equal(listOf(ANNA, BEN))))
                .record(entry(2, euros(30), equal(listOf(ANNA, BEN)), equal(listOf(BEN))))
                .events()
                .last()
        val seen = mutableListOf<Overview?>()
        runBlocking {
            groups.overview(trip, TODAY, Dispatchers.Unconfined).take(3).collect { overview ->
                seen += overview
                when (seen.size) {
                    1 -> groups.append(trip, listOf(paidByTwo), TODAY)
                    2 -> groups.delete(trip)
                }
            }
        }
        assertEquals(
            listOf(listOf(listOf("Ben")), listOf(listOf("Anna", "Ben"), listOf("Ben"))),
            seen.take(2).map { overview -> overview!!.entries.map { it.payers } },
        )
        assertEquals(listOf(euros(120), euros(150)), seen.take(2).map { it!!.total })
        assertNull(seen[2])
    }

    @Test
    fun `a group is read again after every write to its log (EN-9, GR-11)`() {
        groups.create(weekend, TODAY)
        val dinner = entry(2, euros(40), equal(listOf(BEN)), equal(listOf(BEN)))
        val later =
            Log(BEN, trip)
                .create("Wochenende")
                .add(BEN, "Ben")
                .add(ANNA, "Anna")
                .record(entry(1, euros(120), equal(listOf(BEN)), equal(listOf(ANNA, BEN))))
                .record(dinner)
                .delete(dinner.id)
                .events()
                .drop(weekend.size)
        val seen = mutableListOf<Group?>()
        runBlocking {
            groups.group(trip, Dispatchers.Unconfined).take(4).collect { group ->
                seen += group
                when (seen.size) {
                    1 -> groups.append(trip, later.take(1), TODAY)
                    2 -> groups.append(trip, later.drop(1), TODAY)
                    3 -> groups.delete(trip)
                }
            }
        }
        assertEquals(
            listOf(setOf(entryId(1)), setOf(entryId(1), dinner.id), setOf(entryId(1))),
            seen.take(3).map { it!!.entries.keys },
        )
        assertEquals(setOf(dinner.id), seen[2]!!.deleted.keys)
        assertNull(seen[3])
    }

    @Test
    fun `a group's log is read in order and again after every write (HI-3)`() {
        groups.create(weekend, TODAY)
        val dinner = entry(2, euros(40), equal(listOf(BEN)), equal(listOf(BEN)))
        val written =
            Log(BEN, trip)
                .create("Wochenende")
                .add(BEN, "Ben")
                .add(ANNA, "Anna")
                .record(entry(1, euros(120), equal(listOf(BEN)), equal(listOf(ANNA, BEN))))
                .record(dinner)
                .events()
                .last()
        val seen = mutableListOf<List<Event>>()
        runBlocking {
            groups.log(trip, Dispatchers.Unconfined).take(2).collect { log ->
                seen += log
                if (seen.size == 1) groups.append(trip, listOf(written), TODAY)
            }
        }
        assertEquals(weekend, seen[0])
        assertEquals(weekend + written, seen[1])
        assertEquals(emptyList(), groups.events(GroupId.parse("%032x".format(0xdead))))
    }

    @Test
    fun `deleting a group removes its log, retained objects, projection and backend (TR-20, GR-11)`() {
        appendedOneByOne(GROUP, household)
        groups.append(trip, weekend, TODAY)
        for (group in listOf(GROUP, trip)) {
            database.logObjectQueries.retain(group.hex, 1, "ba7c".repeat(8), byteArrayOf(1, 2, 3))
            connected(group)
        }
        groups.actAs(GROUP, ANNA)
        groups.actAs(trip, BEN)
        val others = Projection.TABLES.associateWith { dump(it).filter { trip.hex in it } }

        groups.delete(GROUP)

        for (table in tables()) {
            val column =
                when {
                    "group_id" in columns(table) -> "group_id"
                    table == "group_row" -> "id"
                    else -> continue
                }
            assertEquals(
                listOf("0"),
                rows("SELECT count(*) FROM $table WHERE $column = '${GROUP.hex}'"),
                table,
            )
        }
        assertNull(groups.load(GROUP))
        assertNull(groups.actingAs(GROUP))
        assertNull(groups.backends.settings(GROUP))
        assertNotNull(groups.load(trip))
        assertEquals(BEN, groups.actingAs(trip))
        assertEquals(listOf(trip), groups.backends.connected())
        assertEquals(others, projection())
    }

    @Test
    fun `deleting everything leaves no group in any table and the list empty (TR-20, PR-9)`() {
        appendedOneByOne(GROUP, household)
        groups.append(trip, weekend, TODAY)
        for (group in listOf(GROUP, trip)) {
            database.logObjectQueries.retain(group.hex, 1, "ba7c".repeat(8), byteArrayOf(1, 2, 3))
            connected(group)
        }
        groups.actAs(GROUP, ANNA)
        groups.actAs(trip, BEN)
        val listed = mutableListOf<Int>()
        runBlocking {
            groups.list(TODAY, Dispatchers.Unconfined).take(2).collect { list ->
                listed += list.size
                if (listed.size == 1) groups.deleteAll()
            }
        }
        assertEquals(listOf(2, 0), listed)
        for (group in listOf(GROUP, trip)) {
            assertNull(groups.load(group))
            assertEquals(emptyList(), groups.events(group))
        }
        // projection_state holds only the schema version, no group.
        for (table in tables().filter { it != "projection_state" && !it.startsWith("sqlite_") }) {
            assertEquals(listOf("0"), rows("SELECT count(*) FROM $table"), table)
        }
    }
}
