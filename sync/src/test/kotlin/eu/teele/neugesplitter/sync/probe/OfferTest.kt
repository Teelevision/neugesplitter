package eu.teele.neugesplitter.sync.probe

import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.GroupSync
import eu.teele.neugesplitter.sync.MemoryLog
import eu.teele.neugesplitter.sync.Sealing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class OfferTest {

    private val log = Log().create().add(BEN, "Ben").events()
    private val codec = KageCodec()

    private fun shared(sealing: Sealing, root: ByteArray? = null): BucketStore = runBlocking {
        val store = BucketStore()
        val sync = GroupSync(store, MemoryLog().apply { record(*log.toTypedArray()) }, sealing)
        root?.let { sync.publish(it) }
        sync.pass()
        store
    }

    @Test
    fun `a location without a log is empty, and one without a group's first object foreign (BE-9)`() =
        runBlocking {
            assertEquals(Offer.Empty, offer(BucketStore()))
            val stray = BucketStore().apply { objects["log/0000001"] = byteArrayOf(1) }
            assertEquals(Offer.Foreign, offer(stray))
            val other = BucketStore().apply { objects["log/0000000"] = "{\"a\":1}".toByteArray() }
            assertEquals(Offer.Foreign, offer(other))
            val binary = BucketStore().apply { objects["log/0000000"] = byteArrayOf(0, 1) }
            assertEquals(Offer.Foreign, offer(binary))
        }

    @Test
    fun `an unencrypted group names itself in its first object (format-spec 2_1)`() = runBlocking {
        val offered = offer(shared(Sealing.Plain))
        assertIs<Offer.Open>(offered)
        assertEquals(GROUP, offered.group)
    }

    @Test
    fun `an encrypted group names itself once its key opens the object after the root (format-spec 5_4)`() =
        runBlocking {
            val key = codec.generate()
            val root = "-----BEGIN AGE ENCRYPTED FILE-----".encodeToByteArray()
            val store = shared(Sealing.Encrypted(codec, key), root)

            val offered = offer(store)
            assertIs<Offer.Locked>(offered)
            assertContentEquals(root, offered.root)
            assertEquals(GROUP, locked(store, root, Sealing.Encrypted(codec, key)))
            assertNull(locked(store, root, Sealing.Encrypted(codec, codec.generate())))
            assertNull(locked(BucketStore(), root, Sealing.Encrypted(codec, key)))
        }
}
