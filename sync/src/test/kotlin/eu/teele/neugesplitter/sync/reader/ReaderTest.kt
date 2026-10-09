package eu.teele.neugesplitter.sync.reader

import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.LogObject
import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.MemoryLog
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.ObjectNumber.Companion.ROOT
import eu.teele.neugesplitter.sync.Retained
import eu.teele.neugesplitter.sync.Sealing
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import eu.teele.neugesplitter.sync.writer.Writer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class ReaderTest {

    private val full =
        Log()
            .create()
            .add(BEN, "Ben")
            .add(CLARA, "Clara")
            .apply { (1..20).forEach { update(GroupChanges(description = Change.To("Day $it"))) } }
            .events()
    private val log = full.take(3)

    private fun number(n: Int) = ObjectNumber(n)

    private fun batch(n: Int) = BatchId.parse("%032x".format(n))

    /** Object [n] of an unencrypted log, carrying [events]. */
    private fun BucketStore.put(n: Int, vararg events: Event) {
        objects["log/${number(n).name}"] = LogObject(batch(n), null, events.toList()).write()
    }

    /** The group's [n]th rename. */
    private fun renamed(n: Int): Event = full[2 + n]

    @Test
    fun `objects are applied in order however many are fetched and applied at once (sync-design 5)`() =
        runBlocking {
            val store = BucketStore()
            store.put(0, *log.toTypedArray())
            val more = (1..20).map(::renamed)
            more.forEachIndexed { i, event -> store.put(i + 1, event) }
            val local = MemoryLog()

            val listed = store.listFrom(ROOT)
            val reader = Reader(store, local, Sealing.Plain, parallel = 3, together = 4)
            assertNull(reader.read(null, listed))

            assertEquals(log + more, local.applied)
            assertEquals((0..20).map(::number), local.objects.keys.toList())
        }

    @Test
    fun `an object contradicting the log halts there, after what comes before it is applied`() =
        runBlocking {
            val store = BucketStore()
            store.put(0, *log.toTypedArray())
            (1..5).forEach { store.put(it, renamed(it)) }
            val local = MemoryLog().apply { contradicting += renamed(3) }

            val halted = Reader(store, local, Sealing.Plain).read(null, store.listFrom(ROOT))

            assertEquals(Upload.Halted(number(3), Halt.MALFORMED), halted)
            assertEquals(log + renamed(1) + renamed(2), local.applied)
        }

    @Test
    fun `a missing number halts there, after what comes before it is applied`() = runBlocking {
        val store = BucketStore()
        store.put(0, *log.toTypedArray())
        store.put(1, renamed(1))
        store.put(3, renamed(3))
        val local = MemoryLog()

        val halted = Reader(store, local, Sealing.Plain).read(null, store.listFrom(ROOT))

        assertEquals(Upload.Halted(number(2), Halt.GAP), halted)
        assertEquals(log + renamed(1), local.applied)
    }

    @Test
    fun `a write whose answer was lost is found as this installation's own (format-spec 2_2)`() =
        runBlocking {
            val store = BucketStore()
            val local = MemoryLog().apply { record(*log.toTypedArray()) }
            val batch = local.batch(batch(0))!!
            store.put(0, *batch.events.toTypedArray())

            assertNull(Reader(store, local, Sealing.Plain).read(null, listOf(ROOT)))

            assertNull(local.closed())
            assertEquals(log, local.events())
            assertEquals(Upload.Idle, Writer(store, local, Sealing.Plain).write())
        }

    @Test
    fun `an object that does not follow the one before it halts the group (TR-26a)`() =
        runBlocking {
            val codec = KageCodec()
            val key = codec.generate()
            val root = "an armoured identity".encodeToByteArray()
            val store = BucketStore().apply { objects["log/0000000"] = root }
            val forged = LogObject(batch(1), key.chain.link(root), log)
            store.objects["log/0000001"] = codec.encrypt(key, forged.write())
            val local = MemoryLog(mapOf(ROOT to root))

            val halted =
                Reader(store, local, Sealing.Encrypted(codec, key))
                    .read(Retained(ROOT, root), listOf(number(1)))

            assertEquals(Upload.Halted(number(1), Halt.COMPROMISED), halted)
            assertEquals(emptyList(), local.applied)
        }
}
