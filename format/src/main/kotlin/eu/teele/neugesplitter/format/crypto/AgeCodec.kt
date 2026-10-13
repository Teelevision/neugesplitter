package eu.teele.neugesplitter.format.crypto

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.chain.ChainKey

/**
 * Everything this format asks of age (format-spec 5), and nothing of how: what every group depends
 * on is age's file format, not the library that writes it (decisions.md).
 */
interface AgeCodec {

    /** A new group's key pair, drawn from the platform's secure generator (TR-24). */
    fun generate(): GroupKey

    /**
     * The root object: [key] as an identity file, encrypted to [passphrase] and armoured.
     *
     * @throws TooLittleMemory if this device cannot hold what scrypt takes.
     */
    fun lock(key: GroupKey, passphrase: CharArray): ByteArray

    /**
     * The key in the root object. Deliberately slow, so never on the path of reading an object
     * (TR-26).
     *
     * @throws WrongPassphrase if [passphrase] does not open [root].
     * @throws Unsupported if it would take a scrypt work factor beyond what this device affords.
     * @throws TooLittleMemory if this device cannot hold what scrypt takes.
     * @throws Malformed if [root] is not an armoured age file holding one identity.
     */
    fun unlock(root: ByteArray, passphrase: CharArray): GroupKey

    /** A log object or attachment: [payload] encrypted to [key]'s recipient, in binary. */
    fun encrypt(key: GroupKey, payload: ByteArray): ByteArray

    /** @throws Malformed if [file] is not a binary age file that [key] opens. */
    fun decrypt(key: GroupKey, file: ByteArray): ByteArray
}

/** A group's identity, which reads, writes and chains everything in it (format-spec 5.1). */
class GroupKey internal constructor(internal val identity: String) {

    /** The key [eu.teele.neugesplitter.format.chain.Link]s are computed with. */
    val chain: ChainKey by lazy { ChainKey.of(identity) }

    override fun toString(): String = "GroupKey(redacted)"
}

/** Reported as such and never as a corrupt backend (format-spec 5.1). */
class WrongPassphrase : Exception("the passphrase does not open this group")

/** scrypt at age's work factor takes 256 MiB, more than some devices give an app. */
class TooLittleMemory : Exception("too little memory for scrypt")
