package eu.teele.neugesplitter.sync.reader

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.json.Received
import eu.teele.neugesplitter.sync.Compromised
import eu.teele.neugesplitter.sync.Fetched
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
    private val together: Int = 256,
) {

    /**
     * Applies [listed], the numbers after [head], in order, each once it is shown to follow the one
     * before it (TR-26a). Up to [parallel] are fetched at once, and up to [together] applied in one
     * go, so that the log is replayed once for them rather than once each. Finding the closed
     * batch's own id means a write whose answer was lost landed after all.
     *
     * @return where the log stops being one that can be applied, or null once all of it is.
     * @throws StoreException if the backend could not be asked; what was applied stays.
     */
    suspend fun read(head: Retained?, listed: List<ObjectNumber>): Upload.Halted? {
        val first = after(head)
        val consecutive =
            listed.withIndex().takeWhile { (i, n) -> n.value == first.value + i }.map { it.value }
        val own = local.closed()
        val verified = mutableListOf<Fetched>()
        var previous = head
        for (chunk in consecutive.chunked(parallel)) {
            val fetched = coroutineScope { chunk.map { async { store.get(it) } }.awaitAll() }
            for ((number, bytes) in chunk.zip(fetched)) {
                bytes ?: throw StoreException.Failed("object $number is listed but cannot be read")
                val received =
                    when (val opened = sealing.open(bytes, previous)) {
                        is Opened.Refused ->
                            return flush(verified) ?: Upload.Halted(number, opened.halt)
                        is Opened.Read -> opened.received
                    }
                if (received.carried.batch == own) {
                    flush(verified)?.let {
                        return it
                    }
                    local.written(number, bytes)
                } else {
                    verified += Fetched(number, bytes, received)
                    if (verified.size == together)
                        flush(verified)?.let {
                            return it
                        }
                }
                previous = Retained(number, bytes)
            }
        }
        flush(verified)?.let {
            return it
        }
        if (consecutive.size == listed.size) return null
        return Upload.Halted(ObjectNumber(first.value + consecutive.size), Halt.GAP)
    }

    /**
     * Applies and clears [verified]; the halt at the first that contradicts the log, if one does.
     */
    private suspend fun flush(verified: MutableList<Fetched>): Upload.Halted? {
        if (verified.isEmpty()) return null
        val applied = local.received(verified)
        val halted = verified.getOrNull(applied)?.let { Upload.Halted(it.number, Halt.MALFORMED) }
        verified.clear()
        return halted
    }
}

/** The number of the object after [head]. */
internal fun after(head: Retained?): ObjectNumber =
    head?.let { ObjectNumber(it.number.value + 1) } ?: ObjectNumber.ROOT

/** What [open] made of an object. */
internal sealed interface Opened {

    class Read(val received: Received) : Opened

    /** It cannot be applied, and the group stops syncing there. */
    class Refused(val halt: Halt) : Opened
}

/** [bytes] read as the object after [previous], checked to follow it (TR-26a). */
internal fun Sealing.open(bytes: ByteArray, previous: Retained?): Opened =
    try {
        Opened.Read(read(bytes, previous))
    } catch (_: Compromised) {
        Opened.Refused(Halt.COMPROMISED)
    } catch (_: Unsupported) {
        Opened.Refused(Halt.UNSUPPORTED)
    } catch (_: Malformed) {
        Opened.Refused(Halt.MALFORMED)
    }
