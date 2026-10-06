package eu.teele.neugesplitter.storage

import app.cash.sqldelight.db.SqlDriver
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.storage.projection.Projection

/**
 * The groups this installation holds: each one's local event log, which is the source of truth, and
 * the projection kept up to date from it (architecture.md 4, 5).
 *
 * [driver] must have [Database.Schema] created.
 */
class GroupRepository(private val driver: SqlDriver) {

    private val database = Database(driver)
    private val projection = Projection(driver, database.projectionQueries)

    /** Group [id] as its log produces it, or null if this installation holds no such group. */
    fun load(id: GroupId): Group? = database.transactionWithResult { replayed(id) }

    /**
     * Appends [events] to group [id]'s log and brings the projection up to date, all or nothing
     * (TR-7, TR-17b). A new group's first event is its `group.created`.
     *
     * @return the group with [events] applied.
     * @throws Malformed if an event contradicts the state before it, or breaks a limit of the
     *   format. Nothing is kept then.
     */
    fun append(id: GroupId, events: List<Event>, today: EntryDate): Group =
        database.transactionWithResult {
            require(events.isNotEmpty()) { "nothing to append" }
            val before = replayed(id)
            val after =
                events.fold(before) { state, event ->
                    state?.plus(event) ?: Group.replay(listOf(event))
                }!!
            require(after.id == id) { "events for group ${after.id} appended to group $id" }
            for (event in events) {
                database.eventRowQueries.append(id.hex, EventJson.write(event))
            }
            projection.write(before, after, today)
            after
        }

    /** Records that this installation writes as [member] in group [id] (ID-3). */
    fun actAs(id: GroupId, member: MemberId) {
        database.actingAsQueries.actAs(id.hex, member.hex)
    }

    fun actingAs(id: GroupId): MemberId? =
        database.actingAsQueries.member(id.hex).executeAsOneOrNull()?.let(MemberId::parse)

    /**
     * Brings the projection to this code's version, discarding and rebuilding it if it was built by
     * another, and to [today], the installation's local date (TR-18b, RE-14). Run before reading.
     */
    fun refresh(today: EntryDate) {
        database.transaction {
            if (database.projectionQueries.version().executeAsOneOrNull() != Projection.VERSION) {
                rebuilt(today)
            } else {
                for (hex in database.projectionQueries.stale(today.toString()).executeAsList()) {
                    val group = replayed(GroupId.parse(hex))!!
                    projection.write(group, group, today)
                }
            }
        }
    }

    /** Discards every derived table and replays every group's log into them again (TR-18). */
    fun rebuild(today: EntryDate) {
        database.transaction { rebuilt(today) }
    }

    /**
     * Removes group [id] from this installation: its log, its retained objects, its projection and
     * the member it acts as (TR-20, GR-11).
     */
    fun delete(id: GroupId) {
        database.transaction {
            database.eventRowQueries.forget(id.hex)
            database.logObjectQueries.forget(id.hex)
            database.actingAsQueries.forget(id.hex)
            database.projectionQueries.forget(id.hex)
        }
    }

    private fun rebuilt(today: EntryDate) {
        projection.discard()
        for (hex in database.eventRowQueries.groups().executeAsList()) {
            projection.write(null, replayed(GroupId.parse(hex))!!, today)
        }
        database.projectionQueries.setVersion(Projection.VERSION)
    }

    private fun replayed(id: GroupId): Group? =
        database.eventRowQueries
            .inGroup(id.hex)
            .executeAsList()
            .map(EventJson::read)
            .takeIf { it.isNotEmpty() }
            ?.let(Group::replay)
}
