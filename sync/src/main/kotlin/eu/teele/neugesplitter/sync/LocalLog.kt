package eu.teele.neugesplitter.sync

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.Received

/**
 * One group as this installation holds it, in the terms sync needs: the objects it has retained and
 * the events it has not written yet (architecture.md 1, TR-17d).
 */
interface LocalLog {

    /** The highest-numbered object held, exactly as stored; null while nothing is. */
    suspend fun head(): Retained?

    /**
     * The events waiting to be written, as one batch, or null when none are.
     *
     * The first call closes them under [fresh]. A closed batch keeps its id and its events until
     * [written], across restarts too, so that a write whose response was lost is recognisable
     * (format-spec 2.2); events recorded meanwhile wait for the next batch.
     */
    suspend fun batch(fresh: BatchId): Batch?

    /** The closed batch is object [number], stored as [bytes] (TR-17, TR-17d). */
    suspend fun written(number: ObjectNumber, bytes: ByteArray)

    /**
     * Somebody else's object [number], stored as [bytes]: its events are applied ahead of every
     * event still waiting (TR-17b), keeping the texts it holds them in (TR-17c). A waiting
     * `entry.updated` that no longer applies is restated; the batch keeps its id (sync-design 4).
     *
     * @throws Malformed if they contradict the log before them (format-spec 4.2); nothing is kept.
     */
    suspend fun received(number: ObjectNumber, bytes: ByteArray, received: Received)
}

/** An object as the backend holds it (TR-17). */
class Retained(val number: ObjectNumber, val bytes: ByteArray)

/** Events written together as one object, under the id that recognises it (format-spec 2.2). */
class Batch(val id: BatchId, val events: List<Event>) {

    init {
        require(events.isNotEmpty()) { "a batch carries at least one event" }
    }
}
