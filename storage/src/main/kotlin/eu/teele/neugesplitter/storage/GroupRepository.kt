package eu.teele.neugesplitter.storage

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransactionCallbacks
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Once at first, then whenever any of [queries] may have changed; bursts of changes collapse. */
private fun changes(vararg queries: Query<*>): Flow<Unit> =
    callbackFlow {
            val listener = Query.Listener { trySend(Unit) }
            queries.forEach { it.addListener(listener) }
            send(Unit)
            awaitClose { queries.forEach { it.removeListener(listener) } }
        }
        .conflate()

/**
 * A group's log as parsed, its first [length] events, and the [group] they replay to. Two are equal
 * only if they are one, which a flow can tell apart without comparing whole logs.
 */
class Replayed
internal constructor(internal val length: Long, val events: List<Event>, val group: Group)

/**
 * The groups this installation holds: each one's local event log, which is the source of truth, and
 * the projection kept up to date from it (architecture.md 4, 5).
 *
 * [driver] must have [Database.Schema] created.
 */
class GroupRepository(private val driver: SqlDriver) {

    private val database = Database(driver)
    private val projection = Projection(driver, database.projectionQueries)
    private val rows = database.eventRowQueries

    val backends = Backends(database)

    /** Each group's latest replay. */
    private val replays = ConcurrentHashMap<GroupId, Replayed>()

    /** What a cold [replay] of each group waits on, so that only one of them parses. */
    private val parsing = ConcurrentHashMap<GroupId, Any>()

    /** Counts logs shrunk or removed, after which a replay begun before is not kept. */
    private val generation = AtomicInteger()

    /**
     * Group [id]'s log, parsed, and the group it replays to; null if this installation holds no
     * such group. Only reading the log takes the database; parsing and replaying it leave it to
     * others meanwhile, and callers at once wait for one parse. Not to be called in a transaction.
     */
    fun replay(id: GroupId): Replayed? {
        val began = generation.get()
        var cached: Replayed? = null
        val (length, texts) =
            database.transactionWithResult {
                val length = rows.length(id.hex).executeAsOne()
                cached = replays[id]?.takeIf { it.length == length }
                length to if (cached != null) emptyList() else rows.inGroup(id.hex).executeAsList()
            }
        cached?.let {
            return it
        }
        if (texts.isEmpty()) return null
        return synchronized(parsing.computeIfAbsent(id) { Any() }) {
            replays[id]?.takeIf { it.length == length }
                ?: parsed(length, texts).also { replayed ->
                    synchronized(replays) {
                        if (generation.get() == began) {
                            replays.merge(id, replayed) { old, new ->
                                if (old.length > new.length) old else new
                            }
                        }
                    }
                }
        }
    }

    /** Group [id] as its log produces it, or null if this installation holds no such group. */
    fun load(id: GroupId): Group? = replay(id)?.group

    /** [replay], again whenever group [id]'s log changes. */
    fun replays(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<Replayed?> =
        rows
            .length(id.hex)
            .asFlow()
            .map { replay(id) }
            .distinctUntilChanged { old, new -> old === new }
            .flowOn(context)

    /** [load], again whenever group [id]'s log changes. */
    fun group(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<Group?> =
        replays(id, context).map { it?.group }.distinctUntilChanged()

    /** Group [id]'s log in order, empty if this installation holds no such group (HI-3). */
    fun events(id: GroupId): List<Event> = replay(id)?.events.orEmpty()

    /** [events], again whenever group [id]'s log changes. */
    fun log(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<List<Event>> =
        replays(id, context).map { it?.events.orEmpty() }

    /**
     * The events of group [id] its backend does not hold yet (SY-16), again whenever that changes;
     * none for a group kept only on this device.
     */
    fun unsynced(id: GroupId, context: CoroutineContext = Dispatchers.IO): Flow<Set<EventId>> =
        changes(rows.unsynced(id.hex), database.backendSettingsQueries.settings(id.hex))
            .map { unsyncedEvents(id).mapTo(mutableSetOf()) { it.id } }
            .distinctUntilChanged()
            .flowOn(context)

    private fun unsyncedEvents(id: GroupId): List<Event> =
        if (backends.settings(id) == null) emptyList()
        else rows.unsynced(id.hex).executeAsList().map(EventJson::read)

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
     * The entries of group [id] whose [proposal] the user declined (TI-4), again on every change.
     */
    fun dismissed(
        id: GroupId,
        proposal: Proposal,
        context: CoroutineContext = Dispatchers.IO,
    ): Flow<Set<EntryId>> =
        database.dismissalQueries
            .dismissed(id.hex, proposal.key)
            .asFlow()
            .mapToList(context)
            .map { entries -> entries.mapTo(mutableSetOf(), EntryId::parse) }
            .distinctUntilChanged()

    /**
     * Leaves [entries] of group [id] out of [proposal] from now on, on this installation (TI-4).
     */
    fun dismiss(id: GroupId, proposal: Proposal, entries: Collection<EntryId>) {
        database.transaction {
            entries.forEach { database.dismissalQueries.dismiss(id.hex, it.hex, proposal.key) }
        }
    }

    /**
     * Group [id]'s log as sync writes and receives it (TR-17b, TR-17d). What it receives is brought
     * into the projection as of [today].
     */
    fun local(
        id: GroupId,
        today: () -> EntryDate,
        context: CoroutineContext = Dispatchers.IO,
    ): LocalLog =
        StoredLog(database, projection, id, today, context, { current(id) }) { replays[id] = it }

    /**
     * Every group this installation holds, again whenever a write changes the list. The projection
     * is brought to [today] each time collecting starts, which may be days after this was called.
     */
    fun list(
        today: () -> EntryDate,
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
            .onStart { refresh(today()) }
            .flowOn(context)

    /**
     * Group [id] as its overview shows it, again whenever a write changes it, or null once the
     * installation no longer holds it. It is read from the projection alone, so it shows before the
     * log is replayed. The projection is brought to [today] each time collecting starts.
     *
     * @param newest if not null, how many of the newest entries are read first, without whom they
     *   are for, before all of them are; so that a long overview shows sooner.
     */
    fun overview(
        id: GroupId,
        today: () -> EntryDate,
        context: CoroutineContext = Dispatchers.IO,
        newest: Int? = null,
    ): Flow<Overview?> {
        val queries = database.projectionQueries
        val heading = queries.heading(id.hex)
        // Thousands of rows name the same few members; each is parsed once.
        val members = ConcurrentHashMap<String, MemberId>()
        fun member(hex: String) = members.getOrPut(hex) { MemberId.parse(hex) }
        val entries =
            queries.entries(id.hex) {
                entry,
                kind,
                description,
                date,
                amount,
                edited,
                repeats,
                paid,
                owed,
                payer,
                payerId,
                unseen,
                category,
                emoji ->
                val type = Kind.valueOf(kind.uppercase())
                ListedEntry(
                    id = EntryId.parse(entry),
                    kind = type,
                    description = description,
                    date = EntryDate.parse(date),
                    amount = Amount.ofUnits(amount),
                    payers = listOf(payer),
                    payerIds = setOf(member(payerId)),
                    own =
                        if (paid == null && owed == null) null
                        else {
                            val own = Amount.ofUnits((paid ?: 0) - (owed ?: 0))
                            if (type == Kind.INCOME) -own else own
                        },
                    repeats = repeats,
                    edited = edited != 0L,
                    unseen = unseen,
                    categoryEmoji = emoji,
                    categoryId = category?.takeIf { emoji != null }?.let(CategoryId::parse),
                )
            }
        val unsynced = rows.unsynced(id.hex)
        val shared = database.backendSettingsQueries.settings(id.hex)

        // The newest [limit] entries without whom they are for, or all of them with it.
        fun read(limit: Int?): Overview? =
            database.transactionWithResult {
                heading.executeAsOneOrNull()?.let {
                    val waiting = unsyncedEvents(id).mapNotNullTo(mutableSetOf()) { it.entry() }
                    val beneficiaries = HashMap<String, MutableSet<MemberId>>()
                    if (limit == null)
                        for (row in queries.beneficiaries(id.hex).executeAsList()) beneficiaries
                            .getOrPut(row.entry) { mutableSetOf() }
                            .add(member(row.member))
                    // An entry has a row per payer, and one row too many says there are more.
                    val listed = mutableListOf<MutableList<ListedEntry>>()
                    var complete = true
                    entries.execute { cursor ->
                        while (cursor.next().value) {
                            val row = entries.mapper(cursor)
                            if (listed.lastOrNull()?.first()?.id == row.id) listed.last() += row
                            else if (limit != null && listed.size == limit) {
                                complete = false
                                break
                            } else listed += mutableListOf(row)
                        }
                        QueryResult.Unit
                    }
                    Overview(
                        name = it.name,
                        emoji = it.emoji,
                        colour = it.colour?.let(Colour::parse),
                        scale = Scale.of(it.scale.toInt()),
                        own = it.own?.let(Amount::ofUnits),
                        entries =
                            listed.map { rows ->
                                val first = rows.first()
                                first.copy(
                                    payers = rows.map { it.payers.single() },
                                    payerIds = rows.flatMapTo(mutableSetOf()) { it.payerIds },
                                    beneficiaryIds = beneficiaries[first.id.hex].orEmpty(),
                                    unsynced = first.id in waiting,
                                )
                            },
                        members =
                            queries.members(id.hex).executeAsList().associate {
                                member(it.id) to it.name
                            },
                        total = Amount.ofUnits(queries.spent(id.hex).executeAsOne()),
                        complete = complete,
                    )
                }
            }

        // Either query may be told of a write first; all are read again, together, either way.
        val projected = flow {
            var limit = newest
            changes(heading, entries, unsynced, shared).collect {
                val overview = read(limit)
                limit = null
                emit(overview)
                if (overview?.complete == false) emit(read(null))
            }
        }
        return projected.distinctUntilChanged().onStart { refresh(today()) }.flowOn(context)
    }

    /**
     * Who is in group [id] and what each of them is owed, from the projection alone (ui-design 4),
     * again whenever a write changes it; null once the installation no longer holds it. The
     * projection is brought to [today] each time collecting starts.
     */
    fun people(
        id: GroupId,
        today: () -> EntryDate,
        context: CoroutineContext = Dispatchers.IO,
    ): Flow<People?> {
        val queries = database.projectionQueries
        val heading = queries.heading(id.hex)
        val members =
            queries.people(id.hex) { member, name, removed, settled, involved ->
                ListedMember(
                    MemberId.parse(member),
                    name,
                    removed != 0L,
                    Amount.ofUnits(settled),
                    involved,
                )
            }
        return changes(heading, members)
            .map {
                database.transactionWithResult {
                    heading.executeAsOneOrNull()?.let {
                        People(
                            it.name,
                            it.emoji,
                            Scale.of(it.scale.toInt()),
                            members.executeAsList(),
                        )
                    }
                }
            }
            .distinctUntilChanged()
            .onStart { refresh(today()) }
            .flowOn(context)
    }

    /**
     * How many suggested occurrences of group [id] wait to be confirmed or declined (RE-4, RE-10),
     * again whenever that changes. The projection is brought to [today] each time collecting
     * starts.
     */
    fun pending(
        id: GroupId,
        today: () -> EntryDate,
        context: CoroutineContext = Dispatchers.IO,
    ): Flow<Int> =
        database.projectionQueries
            .pending(id.hex)
            .asFlow()
            .mapToOne(context)
            .map { it.toInt() }
            .distinctUntilChanged()
            .onStart { refresh(today()) }
            .flowOn(context)

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
            val before = current(id)
            val after =
                events.fold(before?.group) { state, event ->
                    state?.plus(event) ?: Group.replay(listOf(event))
                }!!
            require(after.id == id) { "events for group ${after.id} appended to group $id" }
            for (event in events) {
                rows.append(id.hex, EventJson.write(event))
            }
            projection.write(before?.group, after, today)
            val length = rows.length(id.hex).executeAsOne()
            val log = before?.events.orEmpty() + events
            afterCommit { replays[id] = Replayed(length, log, after) }
            after
        }

    /** Records that this installation writes as [member] in group [id] (ID-3). */
    fun actAs(id: GroupId, member: MemberId) {
        database.actingAsQueries.actAs(id.hex, member.hex)
    }

    /** The text of each event in group [id]'s log, in order and exactly as kept (TR-17c). */
    fun texts(id: GroupId): List<String> = rows.inGroup(id.hex).executeAsList()

    /**
     * Stores an exported group's log [texts] as new group [id], with none of it written to any
     * backend, followed by [added]; acts from then on as [me] (DA-3, ID-3).
     *
     * @throws Malformed if the log is not a valid history, or [added] does not apply to it.
     * @throws eu.teele.neugesplitter.format.Unsupported if a later version wrote any of it.
     */
    fun importGroup(
        id: GroupId,
        texts: List<String>,
        me: MemberId,
        added: List<Event>,
        today: EntryDate,
    ): Group =
        database.transactionWithResult {
            require(texts.isNotEmpty()) { "a group has at least its group.created" }
            require(current(id) == null) { "group $id exists already" }
            val log = texts.map { EventJson.imported(it, id) }
            val group = Group.replay(log.map(EventJson::read) + added)
            log.forEach { rows.append(id.hex, it) }
            added.forEach { rows.append(id.hex, EventJson.write(it)) }
            actAs(id, me)
            projection.write(null, group, today)
            group
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
                    val group = current(GroupId.parse(hex))!!.group
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
     * Drops the changes to group [id] its backend does not hold yet, and brings the projection back
     * to what it does hold (SY-17).
     *
     * @return false, dropping nothing, if the backend holds none of the group yet.
     */
    fun discardUnsynced(id: GroupId, today: EntryDate): Boolean =
        database.transactionWithResult {
            if (rows.waiting(id.hex).executeAsOne() == rows.length(id.hex).executeAsOne()) {
                return@transactionWithResult false
            }
            rows.discardWaiting(id.hex)
            forgotten(id)
            database.projectionQueries.forget(id.hex)
            val kept = current(id)!!
            projection.write(null, kept.group, today)
            afterCommit { replays[id] = kept }
            true
        }

    /**
     * Removes group [id] from this installation: its log, its retained objects, its projection, the
     * member it acts as, what has been seen of it and its backend (TR-20, GR-11). Its secrets are
     * [Secrets.forget]'s.
     */
    fun delete(id: GroupId) {
        database.transaction {
            database.eventRowQueries.forget(id.hex)
            forgotten(id)
            database.logObjectQueries.forget(id.hex)
            database.actingAsQueries.forget(id.hex)
            database.backendSettingsQueries.forget(id.hex)
            database.syncStateQueries.forget(id.hex)
            database.unseenQueries.forget(id.hex)
            database.dismissalQueries.forget(id.hex)
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
            database.dismissalQueries.forgetAll()
            database.projectionQueries.forgetAll()
            forgotten(null)
        }
    }

    /**
     * Forgets group [id]'s replay, or every group's if null, and any begun before, as its log
     * shrinks or goes: at once, and again once committed, for one begun meanwhile.
     */
    private fun TransactionCallbacks.forgotten(id: GroupId?) {
        val forget = {
            synchronized(replays) {
                generation.incrementAndGet()
                if (id == null) replays.clear() else replays.remove(id)
            }
        }
        forget()
        afterCommit { forget() }
    }

    private fun rebuilt(today: EntryDate) {
        projection.discard()
        for (hex in rows.groups().executeAsList()) {
            projection.write(null, current(GroupId.parse(hex))!!.group, today)
        }
        database.projectionQueries.setVersion(Projection.VERSION)
    }

    /**
     * Group [id]'s [Replayed], within a transaction. A log only grows, unless it is shrunk or
     * removed and its replay [forgotten], so a replay holds while the log has as many events.
     */
    private fun current(id: GroupId): Replayed? {
        val length = rows.length(id.hex).executeAsOne()
        replays[id]?.let { if (it.length == length) return it }
        val texts = rows.inGroup(id.hex).executeAsList()
        if (texts.isEmpty()) return null
        return parsed(length, texts).also { replays[id] = it }
    }

    private fun parsed(length: Long, texts: List<String>): Replayed {
        val events = texts.map(EventJson::read)
        return Replayed(length, events, Group.replay(events))
    }
}

/** The entry [this] changes, if it changes one (HI-6). */
internal fun Event.entry(): EntryId? =
    when (this) {
        is Event.EntryCreated -> entry.id
        is Event.EntryUpdated -> target
        is Event.EntryDeleted -> target
        else -> null
    }
