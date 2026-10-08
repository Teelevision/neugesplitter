package eu.teele.neugesplitter.sync

import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.sync.ObjectNumber.Companion.ROOT
import eu.teele.neugesplitter.sync.writer.Halt
import eu.teele.neugesplitter.sync.writer.Upload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class GroupSyncTest {

    private val log = Log().create().add(BEN, "Ben").events()

    private val codec = KageCodec()
    private val encrypted = Sealing.Encrypted(codec, codec.generate())
    private val root = "an armoured identity".encodeToByteArray()

    private fun BucketStore.at(n: Int): ByteArray? = objects["log/${ObjectNumber(n).name}"]

    @Test
    fun `an encrypted group's log follows the root object it published (format-spec 5_1)`(): Unit =
        runBlocking {
            val store = BucketStore()
            val local = MemoryLog().apply { record(*log.toTypedArray()) }
            val sync = GroupSync(store, local, encrypted)

            assertTrue(sync.publish(root))
            assertEquals(Upload.Written(ObjectNumber(1)), sync.pass())

            assertContentEquals(root, store.at(0))
            assertContentEquals(root, local.objects.getValue(ROOT))
            val first = encrypted.read(store.at(1)!!, Retained(ROOT, root))
            assertEquals(log, first.carried.events)
        }

    @Test
    fun `a root object that landed though its answer was lost is recognised (format-spec 5_1)`():
        Unit = runBlocking {
        val store = BucketStore().apply { objects["log/0000000"] = root.copyOf() }
        val local = MemoryLog()
        assertTrue(GroupSync(store, local, encrypted).publish(root))
        assertContentEquals(root, local.objects.getValue(ROOT))
    }

    @Test
    fun `a location holding another root object is refused and nothing is retained (BE-9)`(): Unit =
        runBlocking {
            val other = "another group's identity".encodeToByteArray()
            val store = BucketStore().apply { objects["log/0000000"] = other }
            val local = MemoryLog()
            assertFalse(GroupSync(store, local, encrypted).publish(root))
            assertEquals(emptyMap(), local.objects)
            assertContentEquals(other, store.at(0))
        }

    @Test
    fun `a backend missing the highest object held halts and is not written to (TR-40)`(): Unit =
        runBlocking {
            val store = BucketStore().apply { objects["log/0000000"] = root.copyOf() }
            val local = MemoryLog(mapOf(ROOT to root, ObjectNumber(1) to byteArrayOf(1)))
            local.record(*log.toTypedArray())

            val upload = GroupSync(store, local, encrypted).pass()

            assertEquals(Upload.Halted(ObjectNumber(1), Halt.TRUNCATED), upload)
            assertEquals(setOf("log/0000000"), store.objects.keys)
        }

    @Test
    fun `a pass applies what others wrote before writing after it (sync-design 5)`(): Unit =
        runBlocking {
            val store = BucketStore().apply { objects["log/0000000"] = root.copyOf() }
            val anna = MemoryLog(mapOf(ROOT to root))
            val ben = MemoryLog(mapOf(ROOT to root))
            anna.record(log[0])
            GroupSync(store, anna, encrypted).pass()
            ben.record(log[1])

            assertEquals(Upload.Written(ObjectNumber(2)), GroupSync(store, ben, encrypted).pass())
            assertEquals(Upload.Idle, GroupSync(store, anna, encrypted).pass())

            assertEquals(log, ben.applied)
            assertEquals(log, anna.applied)
        }

    @Test
    fun `an installation holding nothing reads an unencrypted group from its root`(): Unit =
        runBlocking {
            val store = BucketStore()
            GroupSync(store, MemoryLog().apply { record(log[0]) }, Sealing.Plain).pass()
            val fresh = MemoryLog().apply { record(log[1]) }

            assertEquals(
                Upload.Written(ObjectNumber(1)),
                GroupSync(store, fresh, Sealing.Plain).pass(),
            )
            assertEquals(log, fresh.applied)
        }

    @Test
    fun `passes asked for together run one after the other (TR-15)`(): Unit = runBlocking {
        val bucket = BucketStore()
        val slow =
            object : LogStore by bucket {
                override suspend fun createIfAbsent(number: ObjectNumber, bytes: ByteArray): Write {
                    delay(10)
                    return bucket.createIfAbsent(number, bytes)
                }
            }
        val local = MemoryLog().apply { record(*log.toTypedArray()) }
        val sync = GroupSync(slow, local, Sealing.Plain)

        val uploads = List(2) { async { sync.pass() } }.awaitAll()

        assertEquals(listOf(Upload.Written(ROOT), Upload.Idle), uploads)
        assertEquals(log, local.applied)
    }
}
