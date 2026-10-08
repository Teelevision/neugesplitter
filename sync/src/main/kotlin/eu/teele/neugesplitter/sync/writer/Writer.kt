package eu.teele.neugesplitter.sync.writer

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.LogObject
import eu.teele.neugesplitter.sync.Compromised
import eu.teele.neugesplitter.sync.LocalLog
import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.Retained
import eu.teele.neugesplitter.sync.Sealing
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.Write
import java.security.SecureRandom

/**
 * Writes a group's waiting events to its backend as the next object (sync-design 4).
 *
 * Writing requires being caught up, so the caller has just read the backend and confirmed the
 * highest object it holds is still there (TR-40). Nothing here may run alongside another write or
 * read of the same group; the caller holds the group's lock (TR-15).
 */
class Writer(
    private val store: LogStore,
    private val local: LocalLog,
    private val sealing: Sealing,
    private val fresh: () -> BatchId = ::randomBatchId,
) {

    /**
     * Writes the waiting batch at the number after the highest one held. Losing that number to
     * somebody else applies their object and tries the next, with the object rebuilt to follow it,
     * its events restated where they no longer apply and its batch id kept (TR-26c, sync-design 4).
     * Finding the batch's own id there means an earlier attempt landed after all, and nothing is
     * written twice.
     *
     * @throws StoreException if the backend could not be asked; the batch keeps its id for the
     *   retry, which is what makes that safe.
     */
    suspend fun write(): Upload {
        var batch = local.batch(fresh()) ?: return Upload.Idle
        var head = local.head()
        while (true) {
            val number = head?.let { ObjectNumber(it.number.value + 1) } ?: ObjectNumber.ROOT
            val payload = LogObject(batch.id, sealing.link(head), batch.events).write()
            val bytes = sealing.seal(payload)
            if (store.createIfAbsent(number, bytes) == Write.CREATED) {
                local.written(number, bytes)
                return Upload.Written(number)
            }
            val taken =
                store.get(number)
                    ?: throw StoreException.Failed("object $number is taken but cannot be read")
            try {
                val received = sealing.read(taken, head)
                if (received.carried.batch == batch.id) {
                    local.written(number, taken)
                    return Upload.Written(number)
                }
                local.received(number, taken, received)
                batch =
                    checkNotNull(local.batch(fresh())) { "a closed batch is kept until written" }
            } catch (e: Compromised) {
                return Upload.Halted(number, Halt.COMPROMISED)
            } catch (e: Unsupported) {
                return Upload.Halted(number, Halt.UNSUPPORTED)
            } catch (e: Malformed) {
                return Upload.Halted(number, Halt.MALFORMED)
            }
            head = Retained(number, taken)
        }
    }
}

/** What a [Writer.write] did. */
sealed interface Upload {

    /** Nothing was waiting. */
    data object Idle : Upload

    /** The batch is object [number]. */
    data class Written(val number: ObjectNumber) : Upload

    /**
     * Object [number], somebody else's, cannot be applied, so nothing after it can be written and
     * the group stops syncing (format-spec 2.2, 5.3, 8). The batch waits.
     */
    data class Halted(val number: ObjectNumber, val reason: Halt) : Upload
}

enum class Halt {
    /** It does not follow the object before it (TR-26a). */
    COMPROMISED,

    /** A later version wrote it (format-spec 8). */
    UNSUPPORTED,

    /** It breaks a rule of the format (format-spec 2.2). */
    MALFORMED,
}

private val random = SecureRandom()

/** A fresh batch id from the platform's secure generator (TR-24). */
private fun randomBatchId(): BatchId {
    val bytes = ByteArray(16).also(random::nextBytes)
    return BatchId.parse(bytes.joinToString("") { "%02x".format(it) })
}
