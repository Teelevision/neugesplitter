package eu.teele.neugesplitter.format.json

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.chain.Link
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A batch's identifier (format-spec 2.2): fresh whenever its events change, the same across retries
 * of one write, so a writer that lost a response can recognise its own object.
 */
@JvmInline
value class BatchId private constructor(val hex: String) {

    override fun toString(): String = hex

    companion object {
        private val WIRE = Regex("[0-9a-f]{32}")

        fun parse(hex: String): BatchId {
            require(WIRE.matches(hex)) { "a batch id must be 32 lowercase hex characters" }
            return BatchId(hex)
        }
    }
}

/**
 * What a log object says (format-spec 2.2). The bytes it was read from are not here: the chain
 * covers those exactly as stored, so the caller keeps them rather than this re-serialising anything
 * (TR-26b).
 *
 * @property prev the chain link, in an encrypted group; checking it is the caller's (TR-26a).
 */
data class LogObject(val batch: BatchId, val prev: Link?, val events: List<Event>) {

    init {
        require(events.isNotEmpty()) { "a log object carries at least one event" }
    }

    /** Compact JSON. The order of the fields is the specification's, and nothing depends on it. */
    fun write(): ByteArray {
        val json = buildJsonObject {
            put(VERSION_FIELD, VERSION)
            put("batch", batch.hex)
            prev?.let { put("prev", it.hex) }
            put("events", buildJsonArray { events.forEach { add(Events.encode(it)) } })
        }
        val bytes = Json.encodeToString(JsonElement.serializer(), json).encodeToByteArray()
        // An object every reader refuses would stop the group for everyone (format-spec 8).
        restricted(bytes)
        return bytes
    }

    companion object {
        /** The format version this implementation reads and writes. */
        const val VERSION: Int = 1

        private const val VERSION_FIELD = "neugesplitter"

        /**
         * @throws Malformed if [bytes] break any rule of version 1 (format-spec 2.2, 4).
         * @throws Unsupported if they were written by a later version (format-spec 8).
         */
        fun read(bytes: ByteArray): LogObject {
            val json = Json.parseToJsonElement(restricted(bytes)) as JsonObject
            // The version comes before every other rule of version 1 (format-spec 2.2).
            val version = json.required(VERSION_FIELD, ::integer)
            if (version > VERSION) throw Unsupported("format version $version")
            if (version != VERSION.toLong()) throw Malformed("format version $version")
            val events = json.required("events", ::array)
            if (events.isEmpty()) throw Malformed("an object carries no events")
            return LogObject(
                json.required("batch", parsed(BatchId::parse)),
                json.optional("prev", parsed(Link::parse)),
                events.map(Events::decode),
            )
        }
    }
}
