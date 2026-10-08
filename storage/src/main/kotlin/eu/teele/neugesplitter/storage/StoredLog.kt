package eu.teele.neugesplitter.storage

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.format.json.Received
import eu.teele.neugesplitter.storage.projection.Projection
import eu.teele.neugesplitter.sync.Batch
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
    private val objects = database.logObjectQueries

    override suspend fun head(): Retained? =
        withContext(context) {
            objects
                .head(id.hex) { number, bytes -> Retained(ObjectNumber(number.toInt()), bytes) }
                .executeAsOneOrNull()
        }

    override suspend fun batch(fresh: BatchId): Batch? =
        withContext(context) {
            database.transactionWithResult {
                closed()
                    ?: run {
                        rows.closeBatch(fresh.hex, id.hex)
                        closed()
                    }
            }
        }

    override suspend fun rooted(bytes: ByteArray) {
        withContext(context) {
            objects.retain(id.hex, ObjectNumber.ROOT.value.toLong(), null, bytes)
        }
    }

    override suspend fun written(number: ObjectNumber, bytes: ByteArray) =
        withContext(context) {
            database.transaction {
                val batch = checkNotNull(closed()) { "no batch was closed" }
                rows.written(number.value.toLong(), id.hex, batch.id.hex)
                objects.retain(id.hex, number.value.toLong(), batch.id.hex, bytes)
            }
        }

    override suspend fun received(number: ObjectNumber, bytes: ByteArray, received: Received) =
        withContext(context) {
            database.transaction {
                val (synced, waiting) =
                    rows.rows(id.hex).executeAsList().partition { it.object_number != null }
                val written = synced.map { EventJson.read(it.json) }
                val pending = waiting.map { EventJson.read(it.json) }
                val from = written.takeIf { it.isNotEmpty() }?.let(Group::replay)
                val before = pending.fold(from, ::applied)
                val onto = received.carried.events.fold(from, ::applied)!!
                if (onto.id != id) throw Malformed("an object belongs to group ${onto.id}")
                val rebased = Group.rebase(pending, from, onto)
                val after = rebased.fold(onto, Group::plus)

                val count = received.texts.size.toLong()
                rows.makeRoom(count, id.hex)
                rows.settle(id.hex)
                val first = (synced.lastOrNull()?.local_order ?: 0) + 1
                received.texts.forEachIndexed { i, text ->
                    rows.received(id.hex, first + i, text, number.value.toLong())
                }
                waiting.forEachIndexed { i, row ->
                    if (rebased[i] != pending[i]) {
                        rows.restate(EventJson.write(rebased[i]), id.hex, row.local_order + count)
                    }
                }
                objects.retain(id.hex, number.value.toLong(), received.carried.batch.hex, bytes)
                projection.write(before, after, today())
            }
        }

    private fun closed(): Batch? {
        val closed = rows.closed(id.hex).executeAsList()
        if (closed.isEmpty()) return null
        return Batch(BatchId.parse(closed.first().batch!!), closed.map { EventJson.read(it.json) })
    }

    private fun applied(state: Group?, event: Event): Group =
        state?.plus(event) ?: Group.replay(listOf(event))
}
