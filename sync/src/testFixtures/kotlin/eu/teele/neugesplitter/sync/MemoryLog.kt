package eu.teele.neugesplitter.sync

import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.Received

/** A group's local log in memory, starting from [retained] objects. */
class MemoryLog(retained: Map<ObjectNumber, ByteArray> = emptyMap()) : LocalLog {

    val objects = sortedMapOf<ObjectNumber, ByteArray>().apply { putAll(retained) }

    /** The events written or received, in object order. */
    val applied = mutableListOf<Event>()

    private var closed: Batch? = null
    private val waiting = mutableListOf<Event>()

    fun record(vararg events: Event) {
        waiting += events
    }

    /** The log as the installation shows it: what is on the backend, then what is not yet. */
    fun events(): List<Event> = applied + closed?.events.orEmpty() + waiting

    override suspend fun head(): Retained? =
        objects.entries.lastOrNull()?.let { (n, bytes) -> Retained(n, bytes) }

    override suspend fun batch(fresh: BatchId): Batch? {
        closed?.let {
            return it
        }
        if (waiting.isEmpty()) return null
        return Batch(fresh, waiting.toList()).also {
            closed = it
            waiting.clear()
        }
    }

    override suspend fun rooted(bytes: ByteArray) {
        objects[ObjectNumber.ROOT] = bytes
    }

    override suspend fun written(number: ObjectNumber, bytes: ByteArray) {
        val batch = checkNotNull(closed) { "no batch was closed" }
        objects[number] = bytes
        applied += batch.events
        closed = null
    }

    override suspend fun received(number: ObjectNumber, bytes: ByteArray, received: Received) {
        objects[number] = bytes
        applied += received.carried.events
    }
}
