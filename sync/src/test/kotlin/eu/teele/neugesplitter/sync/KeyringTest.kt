package eu.teele.neugesplitter.sync

import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.crypto.AgeCodec
import eu.teele.neugesplitter.format.crypto.GroupKey
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class KeyringTest {

    private val key = KageCodec().generate()
    private val root = "root".encodeToByteArray()
    private val other = GroupId.parse("%032x".format(0xbeef))

    /** Opens [root] with "right" only, counting every attempt; waits for [gate] if given. */
    private inner class Counting(private val gate: CompletableDeferred<Unit>? = null) :
        AgeCodec by KageCodec() {
        var unlocks = 0

        override fun unlock(root: ByteArray, passphrase: CharArray): GroupKey {
            unlocks++
            runBlocking { gate?.await() }
            if (!passphrase.contentEquals("right".toCharArray())) throw WrongPassphrase()
            return key
        }
    }

    @Test
    fun `a group is unlocked once per session (TR-26)`(): Unit = runBlocking {
        val codec = Counting()
        val keyring = Keyring(codec)
        assertNull(keyring[GROUP])
        assertSame(key, keyring.unlock(GROUP, root, "right".toCharArray()))
        assertSame(key, keyring.unlock(GROUP, root, "right".toCharArray()))
        assertSame(key, keyring[GROUP])
        assertNull(keyring[other])
        assertEquals(1, codec.unlocks)
    }

    @Test
    fun `the passphrase is wiped whether or not it opens the group (TR-25)`(): Unit = runBlocking {
        val keyring = Keyring(Counting())
        val wrong = "wrong".toCharArray()
        assertFailsWith<WrongPassphrase> { keyring.unlock(GROUP, root, wrong) }
        assertTrue(wrong.all { it == '\u0000' })
        assertNull(keyring[GROUP])
        val right = "right".toCharArray()
        keyring.unlock(GROUP, root, right)
        assertTrue(right.all { it == '\u0000' })
        val again = "right".toCharArray()
        keyring.unlock(GROUP, root, again)
        assertTrue(again.all { it == '\u0000' })
    }

    @Test
    fun `a group being unlocked is reported until it is done`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val keyring = Keyring(Counting(gate))
        val unlocking = async { keyring.unlock(GROUP, root, "right".toCharArray()) }
        while (keyring.unlocking.value.isEmpty()) yield()
        assertEquals(setOf(GROUP), keyring.unlocking.value)
        gate.complete(Unit)
        unlocking.await()
        assertEquals(emptySet(), keyring.unlocking.value)
    }

    @Test
    fun `a group created here is held without unlocking`() {
        val codec = Counting()
        val keyring = Keyring(codec)
        keyring.hold(GROUP, key)
        assertSame(key, keyring[GROUP])
        runBlocking { keyring.unlock(GROUP, root, "wrong".toCharArray()) }
        assertEquals(0, codec.unlocks)
    }
}
