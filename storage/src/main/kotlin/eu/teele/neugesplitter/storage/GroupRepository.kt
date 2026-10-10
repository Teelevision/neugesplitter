package eu.teele.neugesplitter.storage

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.storage.projection.Projection
import eu.teele.neugesplitter.storage.settings.Backends
import eu.teele.neugesplitter.storage.settings.Failure
import eu.teele.neugesplitter.storage.settings.Secrets
import eu.teele.neugesplitter.storage.settings.SyncState
import eu.teele.neugesplitter.sync.LocalLog
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * The groups this installation holds: each one's local event log, which is the source of truth, and
 * the projection kept up to date from it (architecture.md 4, 5).
 *
 * [driver] must have [Database.Schema] created.
 */
class GroupRepository(private val driver: SqlDriver) {

    private val database = Database(driver)
    private val projection = Projection(driver, database.projectionQueries)

    val backends = Backends(database)

    /** Group [id] as its log produces it, or null if this installation holds no such group. */
    fun load(id: GroupId): Group? = database.transactionWithResult { replayed(id) }

    /** [load], again whenever group [id]'s log changes. */
    fun group(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<Group?> =
        database.eventRowQueries
            .length(id.hex)
            .asFlow()
            .map { load(id) }
            .distinctUntilChanged()
            .flowOn(context)

    /** Group [id]'s log in order, empty if this installation holds no such group (HI-3). */
    fun events(id: GroupId): List<Event> =
        database.eventRowQueries.inGroup(id.hex).executeAsList().map(EventJson::read)

    /** [events], again whenever group [id]'s log changes. */
    fun log(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<List<Event>> =
        database.eventRowQueries.length(id.hex).asFlow().map { events(id) }.flowOn(context)

    /**
     * Where in group [id]'s log, counted from 0, each event sits that came from another
     * installation and has not been seen here yet (HI-5, HI-8), again whenever that changes.
     */
    fun unseen(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<Set<Int>> =
        database.unseenQueries
            .positions(id.hex)
            .asFlow()
            .mapToList(context)
            .map { positions -> positions.mapTo(mutableSetOf()) { it.toInt() } }
            .distinctUntilChanged()

    /** Marks the event at [position] in group [id]'s log as seen (HI-5). */
    fun seen(id: GroupId, position: Int) {
        database.unseenQueries.seenAt(id.hex, position.toLong())
    }

    /** Marks every change to entry [entry] of group [id] as seen, as opening it does (HI-6). */
    fun seen(id: GroupId, entry: EntryId) {
        database.unseenQueries.seenEntry(id.hex, entry.hex)
    }

    /** Marks every change to group [id] as seen (HI-7). */
    fun seenAll(id: GroupId) {
        database.unseenQueries.forget(id.hex)
    }

    /**
     * Group [id]'s log as sync writes and receives it (TR-17b, TR-17d). What it receives is brought
     * into the projection as of [today].
     */
    fun local(
        id: GroupId,
        today: () -> EntryDate,
        context: CoroutineContext = Dispatchers.IO,
    ): LocalLog = StoredLog(database, projection, id, today, context)

    /**
     * Every group this installation holds, again whenever a write changes the list. The projection
     * is brought to [today] first.
     */
    fun list(
        today: EntryDate,
        context: CoroutineContext = Dispatchers.IO,
    ): Flow<List<ListedGroup>> =
        database.projectionQueries
            .listed {
                id,
                name,
                emoji,
                colour,
                scale,
                entries,
                own,
                unseen,
                shared,
                succeeded,
                haltedAt,
                halt,
                failure,
                waiting ->
                ListedGroup(
                    id = GroupId.parse(id),
                    name = name,
                    emoji = emoji,
                    colour = colour?.let(Colour::parse),
                    scale = Scale.of(scale.toInt()),
                    entries = entries,
                    own = own?.let(Amount::ofUnits),
                    unseen = unseen,
                    sync =
                        if (!shared) null
                        else
                            SyncState(
                                succeeded = succeeded?.let(Instant::ofEpochMilli),
                                halted =
                                    haltedAt?.let {
                                        Upload.Halted(
                                            ObjectNumber(it.toInt()),
                                            Halt.valueOf(halt!!),
                                        )
                                    },
                                failure = failure?.let(Failure::valueOf),
                                waiting = waiting,
                            ),
                )
            }
            .asFlow()
            .mapToList(context)
            .onStart { refresh(today) }
            .flowOn(context)

    /**
     * Group [id] as its overview shows it, again whenever a write changes it, or null once the
     * installation no longer holds it. The projection is brought to [today] first.
     */
    fun overview(
        id: GroupId,
        today: EntryDate,
        context: CoroutineContext = Dispatchers.IO,
    ): Flow<Overview?> {
        val queries = database.projectionQueries
        val heading = queries.heading(id.hex)
        val entries =
            queries.entries(id.hex) {
                entry,
                kind,
                description,
                date,
                amount,
                edited,
                repeats,
                own,
                payer,
                unseen ->
                ListedEntry(
                    id = EntryId.parse(entry),
                    kind = Kind.valueOf(kind.uppercase()),
                    description = description,
                    date = EntryDate.parse(date),
                    amount = Amount.ofUnits(amount),
                    payers = listOf(payer),
                    own = own?.let(Amount::ofUnits),
                    repeats = repeats,
                    edited = edited != 0L,
                    unseen = unseen,
                )
            }
        // Either query may be told of a write first; both are read again, together, either way.
        return merge(heading.asFlow(), entries.asFlow())
            .map {
                database.transactionWithResult {
                    heading.executeAsOneOrNull()?.let {
                        Overview(
                            name = it.name,
                            emoji = it.emoji,
                            colour = it.colour?.let(Colour::parse),
                            scale = Scale.of(it.scale.toInt()),
                            own = it.own?.let(Amount::ofUnits),
                            entries =
                                entries
                                    .executeAsList()
                                    .groupBy { it.id }
                                    .values
                                    .map { rows ->
                                        rows.first().copy(payers = rows.map { it.payers.single() })
                                    },
                        )
                    }
                }
            }
            .distinctUntilChanged()
            .onStart { refresh(today) }
            .flowOn(context)
    }

    /**
     * Stores a new group from the events [Group.create] returned for it, and acts from then on as
     * the member who wrote them, its creator (ID-2, ID-3).
     *
     * @throws Malformed as [append] does, which includes the group existing already.
     */
    fun create(events: List<Event>, today: EntryDate): Group =
        database.transactionWithResult {
            val created = events.firstOrNull()
            require(created is Event.GroupCreated) { "a group's first event is its group.created" }
            val group = append(created.group.id, events, today)
            actAs(group.id, created.by)
            group
        }

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

    /** The groups held in part, as a join left before choosing who to act as (BE-6). */
    fun unclaimed(): List<GroupId> =
        database.actingAsQueries.unclaimed().executeAsList().map(GroupId::parse)

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
     * Removes group [id] from this installation: its log, its retained objects, its projection, the
     * member it acts as, what has been seen of it and its backend (TR-20, GR-11). Its secrets are
     * [Secrets.forget]'s.
     */
    fun delete(id: GroupId) {
        database.transaction {
            database.eventRowQueries.forget(id.hex)
            database.logObjectQueries.forget(id.hex)
            database.actingAsQueries.forget(id.hex)
            database.backendSettingsQueries.forget(id.hex)
            database.syncStateQueries.forget(id.hex)
            database.unseenQueries.forget(id.hex)
            database.projectionQueries.forget(id.hex)
        }
    }

    /** Removes every group from this installation, as [delete] does for one (TR-20, PR-9). */
    fun deleteAll() {
        database.transaction {
            database.eventRowQueries.forgetAll()
            database.logObjectQueries.forgetAll()
            database.actingAsQueries.forgetAll()
            database.backendSettingsQueries.forgetAll()
            database.syncStateQueries.forgetAll()
            database.unseenQueries.forgetAll()
            database.projectionQueries.forgetAll()
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
        events(id).takeIf { it.isNotEmpty() }?.let(Group::replay)
}
