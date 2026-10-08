package eu.teele.neugesplitter.sync

import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.Received

/** A group's local log in memory, starting from [retained] objects. */
class MemoryLog(retained: Map<ObjectNumber, ByteArray> = emptyMap()) : LocalLog {

    val objects = sortedMapOf<ObjectNumber, ByteArray>().apply { putAll(retained) }

    /** The events written or received, in object order. */
    val applied = mutableListOf<Event>()

    private var closedBatch: Batch? = null
    private val waiting = mutableListOf<Event>()

    fun record(vararg events: Event) {
        waiting += events
    }

    /** The log as the installation shows it: what is on the backend, then what is not yet. */
    fun events(): List<Event> = applied + closedBatch?.events.orEmpty() + waiting

    override suspend fun head(): Retained? =
        objects.entries.lastOrNull()?.let { (n, bytes) -> Retained(n, bytes) }

    override suspend fun batch(fresh: BatchId): Batch? {
        closedBatch?.let {
            return it
        }
        if (waiting.isEmpty()) return null
        return Batch(fresh, waiting.toList()).also {
            closedBatch = it
            waiting.clear()
        }
    }

    override suspend fun closed(): BatchId? = closedBatch?.id

    override suspend fun rooted(bytes: ByteArray) {
        objects[ObjectNumber.ROOT] = bytes
    }

    override suspend fun written(number: ObjectNumber, bytes: ByteArray) {
        val batch = checkNotNull(closedBatch) { "no batch was closed" }
        objects[number] = bytes
        applied += batch.events
        closedBatch = null
    }

    override suspend fun received(number: ObjectNumber, bytes: ByteArray, received: Received) {
        objects[number] = bytes
        applied += received.carried.events
    }
}
