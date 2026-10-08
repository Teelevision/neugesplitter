package eu.teele.neugesplitter.sync.reader

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.sync.Compromised
import eu.teele.neugesplitter.sync.LocalLog
import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.Retained
import eu.teele.neugesplitter.sync.Sealing
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Applies the objects a group's backend holds after the highest one this installation does
 * (sync-design 5). Nothing here may run alongside another read or write of the same group; the
 * caller holds the group's lock (TR-15).
 */
class Reader(
    private val store: LogStore,
    private val local: LocalLog,
    private val sealing: Sealing,
    private val parallel: Int = 8,
) {

    /**
     * Applies [listed], the numbers after [head], in order, each once it is shown to follow the one
     * before it (TR-26a). Up to [parallel] are fetched at once. Finding the closed batch's own id
     * means a write whose answer was lost landed after all.
     *
     * @return where the log stops being one that can be applied, or null once all of it is.
     * @throws StoreException if the backend could not be asked; what was applied stays.
     */
    suspend fun read(head: Retained?, listed: List<ObjectNumber>): Upload.Halted? {
        val first = after(head)
        val consecutive =
            listed.withIndex().takeWhile { (i, n) -> n.value == first.value + i }.map { it.value }
        var previous = head
        for (chunk in consecutive.chunked(parallel)) {
            val fetched = coroutineScope { chunk.map { async { store.get(it) } }.awaitAll() }
            for ((number, bytes) in chunk.zip(fetched)) {
                bytes ?: throw StoreException.Failed("object $number is listed but cannot be read")
                val taken = local.take(sealing, number, bytes, previous, local.closed())
                if (taken is Taken.Refused) return Upload.Halted(number, taken.halt)
                previous = Retained(number, bytes)
            }
        }
        if (consecutive.size == listed.size) return null
        return Upload.Halted(ObjectNumber(first.value + consecutive.size), Halt.GAP)
    }
}

/** The number of the object after [head]. */
internal fun after(head: Retained?): ObjectNumber =
    head?.let { ObjectNumber(it.number.value + 1) } ?: ObjectNumber.ROOT

/** What [take] did with an object. */
internal sealed interface Taken {

    /** It is the batch this installation closed, written after all. */
    data object Own : Taken

    /** Somebody else's, applied. */
    data object Theirs : Taken

    /** It cannot be applied, and the group stops syncing there. */
    data class Refused(val halt: Halt) : Taken
}

/**
 * Takes object [number], stored as [bytes], in after [previous]: as written if it carries the batch
 * id [own], otherwise as somebody else's.
 */
internal suspend fun LocalLog.take(
    sealing: Sealing,
    number: ObjectNumber,
    bytes: ByteArray,
    previous: Retained?,
    own: BatchId?,
): Taken =
    try {
        val received = sealing.read(bytes, previous)
        if (received.carried.batch == own) {
            written(number, bytes)
            Taken.Own
        } else {
            received(number, bytes, received)
            Taken.Theirs
        }
    } catch (_: Compromised) {
        Taken.Refused(Halt.COMPROMISED)
    } catch (_: Unsupported) {
        Taken.Refused(Halt.UNSUPPORTED)
    } catch (_: Malformed) {
        Taken.Refused(Halt.MALFORMED)
    }
