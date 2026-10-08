package eu.teele.neugesplitter.sync

import java.io.IOException

/**
 * The backend, in the design's own terms and none of S3's (TR-29, BE-14): a numbered key space that
 * can create an object only where there is none.
 */
interface LogStore {

    /** Stores [bytes] as object [number] unless somebody already has (sync-design 4). */
    suspend fun createIfAbsent(number: ObjectNumber, bytes: ByteArray): Write

    /** Object [number] exactly as stored, or null when there is none. */
    suspend fun get(number: ObjectNumber): ByteArray?

    /** Every object number from [first] on, in order; names that are not numbers are ignored. */
    suspend fun listFrom(first: ObjectNumber): List<ObjectNumber>

    /** Creates the backend check's `probe` unless it exists (format-spec 2, BE-7). */
    suspend fun createProbe(): Write
}

/** What a create-if-absent did. Losing is an answer, not a failure (TR-30). */
enum class Write {
    CREATED,
    TAKEN,
}

/** A log object's number, which is also its name and its place in the order (format-spec 2). */
@JvmInline
value class ObjectNumber(val value: Int) : Comparable<ObjectNumber> {

    init {
        require(value in 0..MAX) { "not an object number: $value" }
    }

    /** Seven lowercase hex digits, so that names sort in number order. */
    val name: String
        get() = value.toString(16).padStart(7, '0')

    override fun compareTo(other: ObjectNumber): Int = value.compareTo(other.value)

    override fun toString(): String = name

    companion object {
        const val MAX: Int = 0xfffffff

        val ROOT = ObjectNumber(0)

        private val NAME = Regex("[0-9a-f]{7}")

        /** The number a name stands for, or null for a name readers ignore. */
        fun parse(name: String): ObjectNumber? =
            if (NAME.matches(name)) ObjectNumber(name.toInt(16)) else null
    }
}

/** Why the backend did not do what was asked. A `412` is never one of these (TR-30). */
sealed class StoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Nothing answered: no network, a wrong address, a server that is down. */
    class Unreachable(cause: IOException) :
        StoreException("the backend could not be reached: ${cause.message}", cause)

    /** The backend refused these credentials, or refused them this operation. */
    class Denied(detail: String) : StoreException(detail)

    /** The location does not exist. */
    class Missing(detail: String) : StoreException(detail)

    /** Any other answer. */
    class Failed(detail: String) : StoreException(detail)
}
