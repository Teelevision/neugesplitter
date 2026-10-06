package eu.teele.neugesplitter.format

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.format.crypto.KageCodec
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.LogObject
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.next
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KageCodecTest {

    private val codec = KageCodec()
    private val passphrase = "correct horse battery staple"

    @Test
    fun `an object is produced, encrypted, decrypted, chain-verified and re-read byte for byte`() {
        val key = codec.generate()
        var previous = ByteArray(0)
        repeat(3) { n ->
            val written =
                LogObject(
                    BatchId.parse("%032x".format(n)),
                    key.chain.link(previous),
                    Arb.list(arbEvent, 1..5).next(),
                )
            val payload = written.write()
            val file = codec.encrypt(key, payload)
            val received = codec.decrypt(key, file)
            assertContentEquals(payload, received)
            val read = LogObject.read(received)
            assertTrue(
                key.chain.verifies(previous, read.prev!!),
                "object $n does not follow its predecessor",
            )
            assertEquals(written, read)
            previous = received
        }
    }

    @Test
    fun `the root object opens with its passphrase and with no other`() {
        val key = codec.generate()
        val root = codec.lock(key, passphrase.toCharArray())
        assertTrue(root.decodeToString().startsWith("-----BEGIN AGE ENCRYPTED FILE-----\n"))
        val unlocked = codec.unlock(root, passphrase.toCharArray())
        val payload = "{}".encodeToByteArray()
        assertContentEquals(payload, codec.decrypt(unlocked, codec.encrypt(key, payload)))
        assertFailsWith<WrongPassphrase> { codec.unlock(root, "$passphrase.".toCharArray()) }
    }

    @Test
    fun `a root asking for more scrypt work than age's default is not attempted`() {
        val root = codec.lock(codec.generate(), passphrase.toCharArray()).decodeToString()
        val lines = root.lines().filter { it.isNotEmpty() }
        // ISO-8859-1 maps bytes to characters one to one, so the binary payload survives the edit.
        val original =
            Base64.getDecoder()
                .decode(lines.drop(1).dropLast(1).joinToString(""))
                .toString(Charsets.ISO_8859_1)
        val raised = original.replaceFirst(Regex("(-> scrypt \\S+) 18\n"), "$1 30\n")
        assertFalse(raised == original, "the work factor was not found")
        val body =
            Base64.getEncoder().encodeToString(raised.toByteArray(Charsets.ISO_8859_1)).chunked(64)
        val forged =
            (listOf(lines.first()) + body + lines.last()).joinToString("\n", postfix = "\n")
        assertFailsWith<Unsupported> {
            codec.unlock(forged.encodeToByteArray(), passphrase.toCharArray())
        }
    }

    @Test
    fun `an object that does not open with the group's key is malformed`() {
        val key = codec.generate()
        val file = codec.encrypt(key, "{}".encodeToByteArray())
        val tampered = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<Malformed> { codec.decrypt(codec.generate(), file) }
        assertFailsWith<Malformed> { codec.decrypt(key, tampered) }
        assertFailsWith<Malformed> { codec.decrypt(key, file.copyOf(file.size - 1)) }
        assertFailsWith<Malformed> { codec.decrypt(key, codec.lock(key, passphrase.toCharArray())) }
        assertFailsWith<Malformed> { codec.unlock(file, passphrase.toCharArray()) }
    }

    @Test
    fun `a key never prints its identity`() {
        assertEquals("GroupKey(redacted)", codec.generate().toString())
    }
}
