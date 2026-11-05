package eu.teele.neugesplitter.format

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.RENT
import eu.teele.neugesplitter.domain.arbSegment
import eu.teele.neugesplitter.domain.category.Category
import eu.teele.neugesplitter.domain.category.defaultCategories
import eu.teele.neugesplitter.domain.event.CategoryChanges
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupDetails
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.format.chain.Link
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.format.json.LogObject
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.next
import io.kotest.property.checkAll
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class CodecTest {

    private val batch = BatchId.parse("ba7c".repeat(8))
    private val anna = MemberId.parse("a1".repeat(16))

    private fun written(event: Event): JsonObject {
        val bytes = LogObject(batch, null, listOf(event)).write()
        return Json.parseToJsonElement(bytes.decodeToString())
            .jsonObject
            .getValue("events")
            .jsonArray
            .single()
            .jsonObject
    }

    private fun named(vararg changes: Pair<String, Change<*>>): Set<String> =
        changes.filter { it.second is Change.To<*> }.map { it.first }.toSet()

    @Test
    fun `every event survives being written and read`() {
        runBlocking {
            checkAll(Arb.list(arbEvent, 1..8), Arb.boolean()) { events, chained ->
                val prev = if (chained) Link.parse("5a".repeat(32)) else null
                val original = LogObject(batch, prev, events)
                assertEquals(original, LogObject.read(original.write()))
            }
        }
    }

    @Test
    fun `an event's text is the one its log object carries, and reads back (TR-17b)`() {
        runBlocking {
            checkAll(arbEvent) { event ->
                val text = EventJson.write(event)
                assertEquals(written(event), Json.parseToJsonElement(text))
                assertEquals(event, EventJson.read(text))
            }
        }
    }

    @Test
    fun `every month is written as monthlyOn, which readers from before everyMonths know (format-spec 4_2)`() {
        fun interval(months: Int): JsonObject {
            val segment = arbSegment.next().copy(interval = Interval.MonthlyOn(setOf(15), months))
            val event =
                Event.SeriesAmended(
                    EventId.parse("%032x".format(1)),
                    anna,
                    Instant.EPOCH,
                    RENT,
                    EntryDate.parse("2026-01-01"),
                    segment,
                )
            return written(event).getValue("segment").jsonObject.getValue("interval").jsonObject
        }
        assertEquals(Json.parseToJsonElement("""{"monthlyOn":[15]}"""), interval(1))
        assertEquals(
            Json.parseToJsonElement("""{"everyMonths":{"months":3,"days":[15]}}"""),
            interval(3),
        )
    }

    @Test
    fun `an entry update names exactly the fields that changed (TR-47)`() {
        runBlocking {
            checkAll(arbEntryChanges) { changes ->
                val event =
                    Event.EntryUpdated(
                        EventId.parse("%032x".format(1)),
                        anna,
                        Instant.EPOCH,
                        EntryId.parse("e2".repeat(16)),
                        changes,
                    )
                val expected =
                    with(changes) {
                        named(
                            "kind" to kind,
                            "description" to description,
                            "date" to date,
                            "amount" to amount,
                            "payers" to payers,
                            "beneficiaries" to beneficiaries,
                            "note" to note,
                            "attachments" to attachments,
                        )
                    }
                assertEquals(expected, written(event).getValue("entry").jsonObject.keys)
            }
        }
    }

    @Test
    fun `a group update names exactly the fields that changed (TR-47)`() {
        runBlocking {
            checkAll(arbGroupChanges) { changes ->
                val event =
                    Event.GroupUpdated(
                        EventId.parse("%032x".format(1)),
                        anna,
                        Instant.EPOCH,
                        changes,
                    )
                val expected =
                    with(changes) {
                        named(
                            "scale" to scale,
                            "name" to name,
                            "description" to description,
                            "emoji" to emoji,
                            "colour" to colour,
                            "defaultParticipants" to defaultParticipants,
                        )
                    }
                assertEquals(expected, written(event).getValue("group").jsonObject.keys)
            }
        }
    }

    @Test
    fun `clearing a field writes null rather than leaving it out (TR-47)`() {
        val event =
            Event.EntryUpdated(
                EventId.parse("%032x".format(1)),
                anna,
                Instant.EPOCH,
                EntryId.parse("e2".repeat(16)),
                EntryChanges(note = Change.To(null)),
            )
        assertEquals(JsonObject(mapOf("note" to JsonNull)), written(event).getValue("entry"))
    }

    @Test
    fun `category definitions and assignments survive the wire format`() {
        val group = GroupId.parse("ab".repeat(16))
        val category = defaultCategories().first()
        val created =
            Event.GroupCreated(
                EventId.parse("%032x".format(0x101)),
                anna,
                Instant.EPOCH,
                GroupDetails(group, Scale.DEFAULT, "Home"),
            )
        val customCategory =
            Category(CategoryId.parse("ac".repeat(16)), name = "Books", emoji = "📚")
        val categoryCreated =
            Event.CategoryCreated(
                EventId.parse("%032x".format(0x106)),
                anna,
                Instant.EPOCH,
                customCategory,
            )
        val entry =
            arbSegment
                .next()
                .template
                .on(EntryId.parse("%032x".format(0x102)), EntryDate.parse("2026-10-01"))
                .copy(categoryId = category.id)
        val entryCreated =
            Event.EntryCreated(EventId.parse("%032x".format(0x103)), anna, Instant.EPOCH, entry)
        val categoryUpdated =
            Event.CategoryUpdated(
                EventId.parse("%032x".format(0x104)),
                anna,
                Instant.EPOCH,
                category.id,
                CategoryChanges(name = Change.To("Home"), emoji = Change.To("🏡")),
            )

        listOf(created, categoryCreated, entryCreated, categoryUpdated).forEach { event ->
            assertEquals(event, EventJson.read(EventJson.write(event)))
        }

        val clearing =
            Event.EntryUpdated(
                EventId.parse("%032x".format(0x105)),
                anna,
                Instant.EPOCH,
                entry.id,
                EntryChanges(categoryId = Change.To(null)),
            )
        assertEquals(
            JsonNull,
            written(clearing).getValue("entry").jsonObject.getValue("categoryId"),
        )
        assertEquals(clearing, EventJson.read(EventJson.write(clearing)))
    }

    @Test
    fun `a group created event without category snapshots remains valid`() {
        val old =
            """{"id":"${"%032x".format(0x201)}","type":"group.created","by":"${anna.hex}",""" +
                """"at":"2026-01-02T00:00:00Z","group":{"id":"${"ab".repeat(16)}",""" +
                """"scale":2,"name":"Old"}}"""

        val event = EventJson.read(old) as Event.GroupCreated

        assertEquals(event, EventJson.read(EventJson.write(event)))
    }

    @Test
    fun `a group created event with a legacy category snapshot is refused`() {
        val old =
            """{"id":"${"%032x".format(0x201)}","type":"group.created","by":"${anna.hex}",""" +
                """"at":"2026-01-02T00:00:00Z","group":{"id":"${"ab".repeat(16)}",""" +
                """"scale":2,"name":"Old","categories":[]}}"""

        assertFailsWith<Malformed> { EventJson.read(old) }
    }

    @Test
    fun `category created events cannot create built in categories`() {
        val legacy =
            """{"id":"${"%032x".format(0x301)}","type":"category.created","by":"${anna.hex}",""" +
                """"at":"2026-01-02T00:00:00Z","category":{"id":"${"ac".repeat(16)}",""" +
                """"builtin":"housing","emoji":"🏠"}}"""

        assertFailsWith<Malformed> { EventJson.read(legacy) }
        val category = defaultCategories().first()
        assertFailsWith<Malformed> {
            EventJson.write(
                Event.CategoryCreated(
                    EventId.parse("%032x".format(0x302)),
                    anna,
                    Instant.EPOCH,
                    category,
                )
            )
        }
    }

    @Test
    fun `an update leaves every field it does not name untouched, unknown ones included (TR-47)`() {
        val vector =
            File(System.getProperty("vectors"))
                .resolveSibling("vectors/entry-schemes.json")
                .readBytes()
        val e2 = EntryId.parse("e2".repeat(16))
        // E2 carries a field this version does not define, and so does the update.
        val update =
            """{"neugesplitter":1,"batch":"${"0b".repeat(16)}","events":[""" +
                """{"id":"${"%032x".format(0x100)}","type":"entry.updated","by":"${anna.hex}",""" +
                """"at":"2026-01-02T00:00:00Z","target":"${e2.hex}",""" +
                """"entry":{"note":"Changed","category":{"name":"other"}}}]}"""
        val before = Group.replay(LogObject.read(vector).events)
        val read = LogObject.read(update.encodeToByteArray()).events.single() as Event.EntryUpdated
        assertEquals(EntryChanges(note = Change.To("Changed")), read.entry)
        val after = before + read
        assertEquals(before.entries.getValue(e2).copy(note = "Changed"), after.entries.getValue(e2))
        assertEquals(before.entries - e2, after.entries - e2)
    }

    @Test
    fun `received events keep their text exactly as the object holds it (TR-17c)`() {
        val first =
            """{ "id":"${"%032x".format(1)}", "type":"member.renamed", "by":"${anna.hex}",""" +
                """ "at":"2026-01-02T00:00:00Z", "target":"${anna.hex}", "name":"An\u006ea",""" +
                """ "mood":{"later":[1, 2]} }"""
        val second =
            EventJson.write(
                Event.MemberRemoved(EventId.parse("%032x".format(2)), anna, Instant.EPOCH, anna)
            )
        val bytes =
            """{"neugesplitter":1,"batch":"${batch.hex}","events":[ $first ,
                |$second]}"""
                .trimMargin()
                .encodeToByteArray()
        val received = LogObject.receive(bytes)
        assertEquals(listOf(first, second), received.texts)
        assertEquals(LogObject.read(bytes), received.carried)
        assertEquals(received.carried.events, received.texts.map(EventJson::read))
    }
}
