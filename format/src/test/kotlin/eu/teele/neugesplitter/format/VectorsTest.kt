package eu.teele.neugesplitter.format

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.format.chain.ChainKey
import eu.teele.neugesplitter.format.chain.Link
import eu.teele.neugesplitter.format.json.LogObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every vector of format-vectors.json, which every implementation must reproduce (TR-45). */
class VectorsTest {

    private val manifest = File(System.getProperty("vectors"))
    private val vectors = Json.parseToJsonElement(manifest.readText()).jsonObject

    private fun section(name: String): List<JsonObject> =
        vectors.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun file(path: String): ByteArray = manifest.resolveSibling(path).readBytes()

    @Test
    fun `occurrence ids are derived as format-spec 6 says`() {
        for (vector in section("occurrenceIds")) {
            val id =
                EventId.occurrence(
                    SeriesId.parse(vector.string("series")),
                    EntryDate.parse(vector.string("due")),
                )
            assertEquals(vector.string("id"), id.hex, "occurrence of ${vector.string("due")}")
        }
    }

    @Test
    fun `chain keys and links are computed as format-spec 5_3 says`() {
        for (vector in section("chain")) {
            val identity = vector.string("identity")
            val payload =
                vector["payloadFile"]?.let { file(it.jsonPrimitive.content) } ?: ByteArray(0)
            assertEquals(vector.string("chainKey"), hex(ChainKey.derive(identity)))
            val key = ChainKey.of(identity)
            val prev = Link.parse(vector.string("prev"))
            assertEquals(prev, key.link(payload))
            assertTrue(key.verifies(payload, prev))
            assertFalse(
                key.verifies(payload + 0.toByte(), prev),
                "a link must not survive a changed payload",
            )
        }
    }

    @Test
    fun `groups replay to the balances of the vectors`() {
        for (vector in section("groups")) {
            val objects = vector.getValue("objects").jsonArray.map { it.jsonPrimitive.content }
            val events = objects.flatMap { LogObject.read(file(it)).events }
            val balances = Group.replay(events).balances(EntryDate.parse(vector.string("today")))
            val expected =
                vector.getValue("balances").jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(
                expected,
                balances.entries.associate { (id, value) -> id.hex to value.units.toString() },
                "$objects",
            )
        }
    }

    @Test
    fun `accepted objects are read`() {
        for (vector in section("accepted")) {
            val read = runCatching { LogObject.read(vector.string("text").encodeToByteArray()) }
            assertTrue(read.isSuccess, "${vector.string("why")}: ${read.exceptionOrNull()}")
        }
    }

    @Test
    fun `refused objects are refused for the reason the vector gives`() {
        for (vector in section("refused")) {
            val bytes =
                vector["text"]?.let { it.jsonPrimitive.content.encodeToByteArray() }
                    ?: unhex(vector.string("hex"))
            val why = vector.string("why")
            when (val reason = vector.string("as")) {
                "malformed" -> assertFailsWith<Malformed>(why) { LogObject.read(bytes) }
                "unsupported" -> assertFailsWith<Unsupported>(why) { LogObject.read(bytes) }
                else -> error("unknown reason '$reason'")
            }
        }
    }

    @Test
    fun `every limit holds at n and fails at n + 1`() {
        for (vector in section("limits")) {
            val limit = vector.string("limit")
            val n = vector.getValue("n").jsonPrimitive.int
            val bytes = limitObject(limit, n)
            if (vector.getValue("accepted").jsonPrimitive.content.toBoolean()) {
                val read = runCatching { LogObject.read(bytes) }
                assertTrue(read.isSuccess, "$limit at $n: ${read.exceptionOrNull()}")
            } else {
                assertFailsWith<Malformed>("$limit at $n") { LogObject.read(bytes) }
            }
        }
    }

    /** The constructions of format-spec 10. */
    private fun limitObject(limit: String, n: Int): ByteArray {
        val member = "a1".repeat(16)
        fun event(id: Int = 1, name: String = "a", extra: String = "") =
            """{"id":"${"%032x".format(id)}","type":"member.renamed","by":"$member",""" +
                """"at":"2026-01-01T12:00:00Z","target":"$member","name":"$name"$extra}"""
        fun wrap(events: String) =
            """{"neugesplitter":1,"batch":"${"ba7c".repeat(8)}","events":[$events]}"""
        val text =
            when (limit) {
                "size" -> wrap(event()).let { it.dropLast(1) + " ".repeat(n - it.length) + "}" }
                "depth" ->
                    event(extra = ",\"x\":" + "[".repeat(n - 3) + "]".repeat(n - 3)).let(::wrap)
                "string" -> wrap(event(name = "a".repeat(n)))
                "escapedString" -> wrap(event(name = "\\u20ac".repeat(n / 3) + "a".repeat(n % 3)))
                "events" -> wrap((1..n).joinToString(",") { event(id = it) })
                else -> error("unknown limit '$limit'")
            }
        return text.encodeToByteArray()
    }
}

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

internal fun unhex(text: String): ByteArray =
    ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
