package eu.teele.neugesplitter.sync.writer

import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.LogObject
import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.MemoryLog
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.ObjectNumber.Companion.ROOT
import eu.teele.neugesplitter.sync.Retained
import eu.teele.neugesplitter.sync.Sealing
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.Write
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class WriterTest {

    private val log = Log().create().add(BEN, "Ben").add(CLARA, "Clara").events()
    private val created = log[0]
    private val ben = log[1]
    private val clara = log[2]

    private val codec = KageCodec()
    private val key = codec.generate()
    private val encrypted = Sealing.Encrypted(codec, key)
    private val root = "an armoured identity".encodeToByteArray()

    private fun number(n: Int) = ObjectNumber(n)

    /** Batch ids [start], [start] + 1, … */
    private fun ids(start: Int): () -> BatchId {
        var next = start
        return { BatchId.parse("%032x".format(next++)) }
    }

    /** Records every create; fails it while [down], and loses the response to the next [lost]. */
    private class Recording(private val inner: LogStore) : LogStore by inner {
        val attempts = mutableListOf<Pair<ObjectNumber, ByteArray>>()
        var down = false
        var lost = 0

        override suspend fun createIfAbsent(number: ObjectNumber, bytes: ByteArray): Write {
            if (down) throw StoreException.Unreachable(IOException("no route to host"))
            attempts += number to bytes
            val write = inner.createIfAbsent(number, bytes)
            if (lost > 0) {
                lost--
                throw StoreException.Unreachable(IOException("connection reset"))
            }
            return write
        }
    }

    private fun BucketStore.at(n: Int): ByteArray = objects.getValue("log/${number(n).name}")

    @Test
    fun `nothing waiting writes nothing`() = runBlocking {
        val store = BucketStore()
        assertEquals(Upload.Idle, Writer(store, MemoryLog(), Sealing.Plain).write())
        assertEquals(emptySet(), store.objects.keys)
    }

    @Test
    fun `an unencrypted group's first batch is its root object (format-spec 2_1)`() = runBlocking {
        val store = BucketStore()
        val local = MemoryLog().apply { record(created, ben) }
        assertEquals(Upload.Written(ROOT), Writer(store, local, Sealing.Plain, ids(7)).write())
        val stored = LogObject.read(store.at(0))
        assertEquals(
            LogObject(BatchId.parse("%032x".format(7)), null, listOf(created, ben)),
            stored,
        )
        assertContentEquals(store.at(0), local.objects.getValue(ROOT))
        assertEquals(listOf(created, ben), local.applied)
    }

    @Test
    fun `the next batch goes after the highest object held`() = runBlocking {
        val store = BucketStore()
        val local = MemoryLog().apply { record(created) }
        val writer = Writer(store, local, Sealing.Plain)
        writer.write()
        local.record(ben)
        assertEquals(Upload.Written(number(1)), writer.write())
        assertEquals(listOf(ben), LogObject.read(store.at(1)).events)
    }

    @Test
    fun `a lost number applies the winner and writes after it (sync-design 4)`() = runBlocking {
        val store = BucketStore()
        val anna = MemoryLog().apply { record(created) }
        Writer(store, anna, Sealing.Plain).write()
        val other = MemoryLog(mapOf(ROOT to store.at(0))).apply { record(ben) }
        Writer(store, other, Sealing.Plain).write()

        anna.record(clara)
        assertEquals(Upload.Written(number(2)), Writer(store, anna, Sealing.Plain).write())
        assertEquals(listOf(created, ben, clara), anna.events())
        assertEquals(listOf(clara), LogObject.read(store.at(2)).events)
    }

    @Test
    fun `a rebuilt object follows the new predecessor and keeps its batch id (TR-26c)`() =
        runBlocking {
            val store = BucketStore().apply { objects["log/0000000"] = root }
            val other = MemoryLog(mapOf(ROOT to root)).apply { record(created) }
            Writer(store, other, encrypted).write()

            val anna = MemoryLog(mapOf(ROOT to root)).apply { record(ben) }
            val recording = Recording(store)
            assertEquals(Upload.Written(number(2)), Writer(recording, anna, encrypted).write())

            val (lost, won) =
                recording.attempts.map { (_, bytes) -> LogObject.read(codec.decrypt(key, bytes)) }
            assertEquals(listOf(number(1), number(2)), recording.attempts.map { it.first })
            assertEquals(lost.batch, won.batch)
            assertNotEquals(lost.prev, won.prev)
            val read = encrypted.read(store.at(2), Retained(number(1), store.at(1)))
            assertEquals(listOf(ben), read.events)
        }

    @Test
    fun `an encrypted group's first log object follows the empty payload (format-spec 5_3)`():
        Unit = runBlocking {
        val store = BucketStore().apply { objects["log/0000000"] = root }
        val local = MemoryLog(mapOf(ROOT to root)).apply { record(created) }
        assertEquals(Upload.Written(number(1)), Writer(store, local, encrypted).write())
        assertEquals(
            key.chain.link(ByteArray(0)),
            encrypted.read(store.at(1), Retained(ROOT, root)).prev,
        )
    }

    @Test
    fun `an encrypted group without its root object is not written`(): Unit = runBlocking {
        val local = MemoryLog().apply { record(created) }
        assertFailsWith<IllegalStateException> { Writer(BucketStore(), local, encrypted).write() }
    }

    @Test
    fun `an interrupted write that landed is recognised by its batch id (TR-48)`() = runBlocking {
        val store = Recording(BucketStore()).apply { lost = 1 }
        val local = MemoryLog().apply { record(created) }
        val writer = Writer(store, local, Sealing.Plain, ids(1))
        assertFailsWith<StoreException.Unreachable> { writer.write() }
        assertNull(local.head())

        assertEquals(Upload.Written(ROOT), writer.write())
        assertEquals(listOf(ROOT, ROOT), store.attempts.map { it.first })
        assertEquals(listOf(created), local.events())
        assertEquals(1, local.objects.size)
    }

    @Test
    fun `an interrupted write somebody else's beat is applied and written after (TR-48)`() =
        runBlocking {
            val bucket = BucketStore()
            val anna = MemoryLog().apply { record(created) }
            Writer(bucket, anna, Sealing.Plain).write()
            anna.record(clara)
            val store = Recording(bucket).apply { down = true }
            val writer = Writer(store, anna, Sealing.Plain, ids(1))
            assertFailsWith<StoreException.Unreachable> { writer.write() }

            val other = MemoryLog(mapOf(ROOT to bucket.at(0))).apply { record(ben) }
            Writer(bucket, other, Sealing.Plain).write()
            store.down = false
            assertEquals(Upload.Written(number(2)), writer.write())
            assertEquals(listOf(created, ben, clara), anna.events())
            assertEquals(BatchId.parse("%032x".format(1)), LogObject.read(bucket.at(2)).batch)
        }

    @Test
    fun `a winner that does not follow its predecessor halts the group (TR-26a)`() = runBlocking {
        val store = BucketStore().apply { objects["log/0000000"] = root }
        // The first log object follows the empty payload, not the root object's bytes.
        val forged = LogObject(BatchId.parse("f".repeat(32)), key.chain.link(root), listOf(ben))
        store.objects["log/0000001"] = codec.encrypt(key, forged.write())

        val anna = MemoryLog(mapOf(ROOT to root)).apply { record(clara) }
        assertEquals(
            Upload.Halted(number(1), Halt.COMPROMISED),
            Writer(store, anna, encrypted).write(),
        )
        assertEquals(listOf(clara), anna.events())
        assertEquals(setOf("log/0000000", "log/0000001"), store.objects.keys)
    }

    @Test
    fun `a winner from a later version halts the group (format-spec 8)`() = runBlocking {
        val store = BucketStore()
        store.objects["log/0000000"] =
            """{"neugesplitter":2,"batch":"${"0".repeat(32)}","events":[]}""".encodeToByteArray()
        val anna = MemoryLog().apply { record(created) }
        assertEquals(
            Upload.Halted(ROOT, Halt.UNSUPPORTED),
            Writer(store, anna, Sealing.Plain).write(),
        )
        assertEquals(listOf(created), anna.events())
    }

    @Test
    fun `a malformed winner halts the group (format-spec 2_2)`() = runBlocking {
        val store = BucketStore()
        store.objects["log/0000000"] = "{}".encodeToByteArray()
        val anna = MemoryLog().apply { record(created) }
        assertEquals(
            Upload.Halted(ROOT, Halt.MALFORMED),
            Writer(store, anna, Sealing.Plain).write(),
        )
    }
}
