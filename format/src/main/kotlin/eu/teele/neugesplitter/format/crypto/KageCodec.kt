package eu.teele.neugesplitter.format.crypto

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.format.Unsupported
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.text.Normalizer
import kage.Age
import kage.Identity
import kage.crypto.scrypt.ScryptIdentity
import kage.crypto.scrypt.ScryptRecipient
import kage.crypto.x25519.X25519Identity
import kage.errors.ScryptIdentityException
import kage.format.AgeStanza

/** [AgeCodec] over kage, the implementation TR-23 settled on (decisions.md). */
class KageCodec : AgeCodec {

    override fun generate(): GroupKey = GroupKey(X25519Identity.new().encodeToString())

    override fun lock(key: GroupKey, passphrase: CharArray): ByteArray {
        val password = utf8(passphrase)
        try {
            val out = ByteArrayOutputStream()
            Age.encryptStream(listOf(ScryptRecipient(password, WORK_FACTOR)), out, true).use {
                it.write("${key.identity}\n".toByteArray(Charsets.US_ASCII))
            }
            return out.toByteArray()
        } catch (_: OutOfMemoryError) {
            throw TooLittleMemory()
        } finally {
            password.fill(0)
        }
    }

    override fun unlock(root: ByteArray, passphrase: CharArray): GroupKey {
        if (!root.startsWith(ARMOUR)) throw Malformed("the root object is not an armoured age file")
        val password = utf8(passphrase)
        val plaintext =
            try {
                open(Passphrase(password), root)
            } catch (e: WrongPassphrase) {
                throw e
            } catch (e: Unsupported) {
                throw e
            } catch (_: OutOfMemoryError) {
                throw TooLittleMemory()
            } catch (_: Exception) {
                throw Malformed("the root object is not an age file a passphrase opens")
            } finally {
                password.fill(0)
            }
        return GroupKey(identityIn(plaintext.decodeToString()))
    }

    override fun encrypt(key: GroupKey, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Age.encryptStream(listOf(X25519Identity.decode(key.identity).recipient()), out).use {
            it.write(payload)
        }
        return out.toByteArray()
    }

    override fun decrypt(key: GroupKey, file: ByteArray): ByteArray {
        if (!file.startsWith(BINARY)) throw Malformed("an object is not a binary age file")
        return try {
            open(X25519Identity.decode(key.identity), file)
        } catch (_: Exception) {
            throw Malformed("an object does not open with the group's key")
        }
    }

    private fun open(identity: Identity, file: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { Age.decryptStream(listOf(identity), file.inputStream(), it) }
            .toByteArray()

    /** Tells a wrong passphrase apart from a broken file, which kage reports alike. */
    private class Passphrase(password: ByteArray) : Identity {
        private val scrypt = ScryptIdentity(password, WORK_FACTOR)

        override fun unwrap(stanzas: List<AgeStanza>): ByteArray {
            val factor =
                stanzas.singleOrNull { it.type == "scrypt" }?.args?.getOrNull(1)?.toIntOrNull()
            if (factor != null && factor > WORK_FACTOR)
                throw Unsupported("a scrypt work factor of $factor")
            try {
                return scrypt.unwrap(stanzas)
            } catch (e: ScryptIdentityException) {
                // Only a failed decryption of the file key carries a cause; a bad stanza does not.
                if (e.cause != null) throw WrongPassphrase()
                throw e
            }
        }
    }

    private companion object {
        /** age's own default, which format-spec 5.1 forbids lowering; the tool never raises it. */
        const val WORK_FACTOR = 18

        val ARMOUR = "-----BEGIN AGE ENCRYPTED FILE-----".toByteArray(Charsets.US_ASCII)
        val BINARY = "age-encryption.org/v1\n".toByteArray(Charsets.US_ASCII)

        fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        /** NFC, then UTF-8 (format-spec 5.1); the [String] between is beyond reach to clear. */
        fun utf8(passphrase: CharArray): ByteArray =
            Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC)
                .encodeToByteArray(throwOnInvalidSequence = true)

        /** The one identity of an identity file, which may also hold comments and blank lines. */
        fun identityIn(file: String): String {
            val lines =
                file.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val identity =
                lines.singleOrNull()
                    ?: throw Malformed("the root object holds ${lines.size} identities")
            if (!identity.startsWith("AGE-SECRET-KEY-1"))
                throw Malformed("the root object holds no X25519 identity")
            try {
                X25519Identity.decode(identity)
            } catch (_: Exception) {
                throw Malformed("the root object holds an identity that does not decode")
            }
            return identity
        }
    }
}
