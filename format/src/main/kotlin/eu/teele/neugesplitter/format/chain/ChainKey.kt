package eu.teele.neugesplitter.format.chain

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A `prev`: 32 bytes as 64 hex characters (format-spec 5.3). */
@JvmInline
value class Link private constructor(val hex: String) {

    internal val bytes: ByteArray
        get() = ByteArray(SIZE) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    override fun toString(): String = hex

    companion object {
        private const val SIZE = 32
        private val WIRE = Regex("[0-9a-f]{${2 * SIZE}}")

        fun parse(hex: String): Link {
            require(WIRE.matches(hex)) { "a link must be ${2 * SIZE} lowercase hex characters" }
            return Link(hex)
        }

        internal fun of(bytes: ByteArray): Link = Link(bytes.joinToString("") { "%02x".format(it) })
    }
}

/**
 * The key an encrypted group's links are computed with (format-spec 5.3).
 *
 * The chain is this project's construction, not age's, so it is built here from the platform's
 * HMAC; nothing of age is reimplemented (TR-22). A link covers the exact bytes an object was
 * encrypted from, which is why the caller keeps them rather than this re-serialising anything
 * (TR-26b).
 */
class ChainKey private constructor(private val key: SecretKeySpec) {

    /** The `prev` of the object after one whose payload was [previous]; empty for the first. */
    fun link(previous: ByteArray): Link = Link.of(mac(previous))

    /** Whether [prev] follows [previous], compared in constant time (TR-26a). */
    fun verifies(previous: ByteArray, prev: Link): Boolean =
        MessageDigest.isEqual(mac(previous), prev.bytes)

    private fun mac(payload: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(key) }.doFinal(payload)

    companion object {
        private const val HMAC = "HmacSHA256"
        private val INFO = "neugesplitter/chain/v1".toByteArray(Charsets.US_ASCII)

        /** @param identity the group's `AGE-SECRET-KEY-1…`, as the root object holds it. */
        fun of(identity: String): ChainKey = ChainKey(SecretKeySpec(derive(identity), HMAC))

        /**
         * HKDF-SHA256 (RFC 5869) with an empty salt and one block of output.
         *
         * An empty salt is the same HMAC key as 32 zero bytes, because HMAC pads its key with zeros
         * (RFC 2104); `javax.crypto` refuses a key of length zero, so the zeros are written out.
         */
        internal fun derive(identity: String): ByteArray {
            val ikm = identityBytes(identity)
            try {
                val prk = hmac(ByteArray(32), ikm)
                return hmac(prk, INFO + 1.toByte())
            } finally {
                ikm.fill(0)
            }
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)
    }
}
