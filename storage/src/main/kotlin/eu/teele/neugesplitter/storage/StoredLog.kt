package eu.teele.neugesplitter.storage

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.storage.projection.Projection
import eu.teele.neugesplitter.sync.Batch
import eu.teele.neugesplitter.sync.Fetched
import eu.teele.neugesplitter.sync.LocalLog
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.Retained
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Group [id]'s log in the database, as sync sees it. Each call is one transaction. */
internal class StoredLog(
    private val database: Database,
    private val projection: Projection,
    private val id: GroupId,
    private val today: () -> EntryDate,
    private val context: CoroutineContext,
) : LocalLog {

    private val rows = database.eventRowQueries
    private val retained = database.logObjectQueries
    private val unseen = database.unseenQueries

    override suspend fun head(): Retained? =
        withContext(context) {
            retained
                .head(id.hex) { number, bytes -> Retained(ObjectNumber(number.toInt()), bytes) }
                .executeAsOneOrNull()
        }

    override suspend fun batch(fresh: BatchId): Batch? =
        withContext(context) {
            database.transactionWithResult {
                closedBatch()
                    ?: run {
                        rows.closeBatch(fresh.hex, id.hex)
                        closedBatch()
                    }
            }
        }

    override suspend fun closed(): BatchId? =
        withContext(context) {
            rows.closed(id.hex).executeAsList().firstOrNull()?.batch?.let(BatchId::parse)
        }

    override suspend fun rooted(bytes: ByteArray) {
        withContext(context) {
            retained.retain(id.hex, ObjectNumber.ROOT.value.toLong(), null, bytes)
        }
    }

    override suspend fun written(number: ObjectNumber, bytes: ByteArray) =
        withContext(context) {
            database.transaction {
                val batch = checkNotNull(closedBatch()) { "no batch was closed" }
                rows.written(number.value.toLong(), id.hex, batch.id.hex)
                retained.retain(id.hex, number.value.toLong(), batch.id.hex, bytes)
            }
        }

    override suspend fun received(objects: List<Fetched>): Int =
        withContext(context) {
            database.transactionWithResult {
                val (synced, waiting) =
                    rows.rows(id.hex).executeAsList().partition { it.object_number != null }
                val written = synced.map { EventJson.read(it.json) }
                val pending = waiting.map { EventJson.read(it.json) }
                val from = written.takeIf { it.isNotEmpty() }?.let(Group::replay)
                val before = pending.fold(from, ::applied)
                var onto = from
                var rebased = pending
                var taken = 0
                for (fetched in objects) {
                    try {
                        val next = fetched.received.carried.events.fold(onto, ::applied)!!
                        if (next.id != id) throw Malformed("an object belongs to group ${next.id}")
                        rebased = Group.rebase(pending, from, next)
                        onto = next
                    } catch (_: Malformed) {
                        break
                    }
                    taken++
                }
                if (taken == 0) return@transactionWithResult 0
                val after = rebased.fold(onto!!, Group::plus)

                val applied = objects.take(taken)
                val count = applied.sumOf { it.received.texts.size }.toLong()
                rows.makeRoom(count, id.hex)
                rows.settle(id.hex)
                // What a join fetches, before anybody is acted as, is the history the user joins.
                val joined = database.actingAsQueries.member(id.hex).executeAsOneOrNull() != null
                var order = (synced.lastOrNull()?.local_order ?: 0) + 1
                for (fetched in applied) {
                    val number = fetched.number.value.toLong()
                    val carried = fetched.received.carried.events
                    for ((i, text) in fetched.received.texts.withIndex()) {
                        if (joined) unseen.mark(id.hex, order, carried[i].entry()?.hex)
                        rows.received(id.hex, order++, text, number)
                    }
                    val batch = fetched.received.carried.batch.hex
                    retained.retain(id.hex, number, batch, fetched.bytes)
                }
                waiting.forEachIndexed { i, row ->
                    if (rebased[i] != pending[i]) {
                        rows.restate(EventJson.write(rebased[i]), id.hex, row.local_order + count)
                    }
                }
                projection.write(before, after, today())
                taken
            }
        }

    private fun closedBatch(): Batch? {
        val closed = rows.closed(id.hex).executeAsList()
        if (closed.isEmpty()) return null
        return Batch(BatchId.parse(closed.first().batch!!), closed.map { EventJson.read(it.json) })
    }

    private fun applied(state: Group?, event: Event): Group =
        state?.plus(event) ?: Group.replay(listOf(event))

    private fun Event.entry(): EntryId? =
        when (this) {
            is Event.EntryCreated -> entry.id
            is Event.EntryUpdated -> target
            is Event.EntryDeleted -> target
            else -> null
        }
}
