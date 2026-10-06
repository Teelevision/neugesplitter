package eu.teele.neugesplitter.format.chain

/*
 * The one piece of Bech32 (BIP 173) the chain needs: the 32 bytes an age identity decodes to
 * (format-spec 5.3). It is an encoding rather than cryptography, so decoding it here reimplements
 * nothing of age (TR-22). No message ever quotes the identity, which is the group's key.
 */

private const val PREFIX = "age-secret-key-"
private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private const val CHECKSUM = 6
private const val SIZE = 32
private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

/** @throws IllegalArgumentException if [identity] is not an age X25519 identity. */
internal fun identityBytes(identity: String): ByteArray {
    // Bech32 is one case or the other, never both; age writes identities in upper case.
    require(identity == identity.uppercase() || identity == identity.lowercase()) {
        "an identity mixes upper and lower case"
    }
    val text = identity.lowercase()
    val separator = text.lastIndexOf('1')
    require(separator >= 0 && text.substring(0, separator) == PREFIX) { "not an age identity" }
    val data = IntArray(text.length - separator - 1) { CHARSET.indexOf(text[separator + 1 + it]) }
    require(data.size > CHECKSUM && data.all { it >= 0 }) {
        "an identity holds a character Bech32 does not"
    }
    require(polymod(expand(PREFIX) + data) == 1) { "an identity's checksum does not match" }
    return regroup(data.copyOf(data.size - CHECKSUM))
}

private fun polymod(values: IntArray): Int {
    var checksum = 1
    for (value in values) {
        val top = checksum ushr 25
        checksum = (checksum and 0x1ffffff) shl 5 xor value
        for (i in GENERATOR.indices) {
            if (((top ushr i) and 1) == 1) checksum = checksum xor GENERATOR[i]
        }
    }
    return checksum
}

private fun expand(prefix: String): IntArray =
    (prefix.map { it.code ushr 5 } + 0 + prefix.map { it.code and 31 }).toIntArray()

/** Five-bit groups to bytes, where the padding must be shorter than a group and all zero. */
private fun regroup(groups: IntArray): ByteArray {
    val bytes = ByteArray(groups.size * 5 / 8)
    var accumulator = 0
    var bits = 0
    var index = 0
    for (group in groups) {
        accumulator = (accumulator shl 5 or group) and 0xfff
        bits += 5
        if (bits >= 8) {
            bits -= 8
            bytes[index++] = (accumulator ushr bits).toByte()
        }
    }
    require(bits < 5 && (accumulator and (1 shl bits) - 1) == 0) { "an identity is padded wrongly" }
    require(bytes.size == SIZE) { "an identity is not $SIZE bytes" }
    return bytes
}
