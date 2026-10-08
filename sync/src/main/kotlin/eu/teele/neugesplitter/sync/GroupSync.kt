package eu.teele.neugesplitter.sync

import eu.teele.neugesplitter.sync.ObjectNumber.Companion.ROOT
import eu.teele.neugesplitter.sync.reader.Reader
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import eu.teele.neugesplitter.sync.writer.Writer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One group's sync against [store], one step at a time however many ask for it: a deferred poll
 * firing during a manual sync waits for it (TR-15). Use one per group.
 */
class GroupSync(private val store: LogStore, private val local: LocalLog, sealing: Sealing) {

    private val reader = Reader(store, local, sealing)
    private val writer = Writer(store, local, sealing)
    private val lock = Mutex()

    /**
     * Lists from the highest object this installation holds, confirming the backend still holds it
     * too (TR-40), applies whatever follows it, and writes what is waiting (sync-design 5). A
     * backend that has gone backwards, or holds an object that cannot be applied, is never written
     * to.
     *
     * @throws StoreException if the backend could not be asked.
     */
    suspend fun pass(): Upload =
        lock.withLock {
            val head = local.head()
            val listed = store.listFrom(head?.number ?: ROOT)
            val halted =
                when {
                    head == null -> reader.read(null, listed)
                    listed.firstOrNull() != head.number ->
                        Upload.Halted(head.number, Halt.TRUNCATED)
                    else -> reader.read(head, listed.drop(1))
                }
            halted ?: writer.write()
        }

    /**
     * Puts a newly encrypted group's [root] object first in its location and retains it, so that
     * its log follows (format-spec 5.1). Finding the same bytes there means an earlier attempt
     * landed.
     *
     * @return false, retaining nothing, if the location holds another root object (BE-9).
     * @throws StoreException if the backend could not be asked.
     */
    suspend fun publish(root: ByteArray): Boolean =
        lock.withLock {
            val there =
                store.createIfAbsent(ROOT, root) == Write.CREATED ||
                    store.get(ROOT)?.contentEquals(root) == true
            if (there) local.rooted(root)
            there
        }
}
