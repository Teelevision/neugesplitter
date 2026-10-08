package eu.teele.neugesplitter.sync

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.chain.Link
import eu.teele.neugesplitter.format.crypto.AgeCodec
import eu.teele.neugesplitter.format.crypto.GroupKey
import eu.teele.neugesplitter.format.json.LogObject
import eu.teele.neugesplitter.format.json.Received

/** How a group's objects are stored: as their payload, or encrypted to its key (format-spec 5). */
sealed interface Sealing {

    /** What is stored for [payload]. */
    fun seal(payload: ByteArray): ByteArray

    /**
     * The `prev` of the object after [previous]; null in an unencrypted group.
     *
     * @throws IllegalStateException in an encrypted group holding no root object.
     */
    fun link(previous: Retained?): Link?

    /**
     * What object [stored] says, once it is shown to follow [previous] (TR-26a).
     *
     * @throws Malformed if it breaks a rule of the format or does not decrypt (format-spec 2.2).
     * @throws Unsupported if a later version wrote it (format-spec 8).
     * @throws Compromised if it does not follow [previous] (format-spec 5.3).
     */
    fun read(stored: ByteArray, previous: Retained?): Received

    data object Plain : Sealing {

        override fun seal(payload: ByteArray): ByteArray = payload

        override fun link(previous: Retained?): Link? = null

        override fun read(stored: ByteArray, previous: Retained?): Received =
            LogObject.receive(stored)
    }

    class Encrypted(private val codec: AgeCodec, private val key: GroupKey) : Sealing {

        override fun seal(payload: ByteArray): ByteArray = codec.encrypt(key, payload)

        override fun link(previous: Retained?): Link = key.chain.link(payload(previous))

        override fun read(stored: ByteArray, previous: Retained?): Received {
            val received = LogObject.receive(codec.decrypt(key, stored))
            val prev = received.carried.prev ?: throw Compromised("an object carries no link")
            if (!key.chain.verifies(payload(previous), prev)) {
                throw Compromised("an object does not follow the one before it")
            }
            return received
        }

        /** The bytes [previous] was encrypted from; the first log object follows none. */
        private fun payload(previous: Retained?): ByteArray {
            checkNotNull(previous) { "an encrypted group's log follows its root object" }
            return if (previous.number == ObjectNumber.ROOT) ByteArray(0)
            else codec.decrypt(key, previous.bytes)
        }

        override fun toString(): String = "Encrypted"
    }
}

/** The history has been altered; the group is shown as compromised, never repaired (TR-26a). */
class Compromised(message: String) : Exception(message)
