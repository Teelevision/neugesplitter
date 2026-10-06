package eu.teele.neugesplitter.format.json

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.event.GroupDetails
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.AttachmentName
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.format.Unsupported
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The events of format-spec 4 and their JSON.
 *
 * Decoding refuses what version 1 forbids and ignores what it does not define. Encoding writes the
 * fields in the order the specification lists them, which nothing depends on, and an update names
 * only the fields that changed (TR-47).
 */
internal object Events {

    fun decode(value: JsonElement): Event {
        val json = obj(value, "event")
        val id = json.required("id", eventId)
        val type = json.required("type", ::text)
        val by = json.required("by", memberId)
        val at = json.required("at", instant)
        return try {
            decode(json, type, id, by, at)
        } catch (e: Malformed) {
            throw Malformed("event $id, $type: ${e.message}")
        }
    }

    private fun decode(json: JsonObject, type: String, id: EventId, by: MemberId, at: Instant) =
        when (type) {
            "group.created" -> Event.GroupCreated(id, by, at, json.required("group", groupDetails))
            "group.updated" -> Event.GroupUpdated(id, by, at, json.required("group", groupChanges))
            "member.added" -> Event.MemberAdded(id, by, at, json.required("member", member))
            "member.renamed" ->
                Event.MemberRenamed(
                    id,
                    by,
                    at,
                    json.required("target", memberId),
                    json.required("name", ::text),
                )
            "member.removed" -> Event.MemberRemoved(id, by, at, json.required("target", memberId))
            "entry.created" -> Event.EntryCreated(id, by, at, json.required("entry", entry))
            "entry.updated" ->
                Event.EntryUpdated(
                    id,
                    by,
                    at,
                    json.required("target", entryId),
                    json.required("entry", entryChanges),
                )
            "entry.deleted" -> Event.EntryDeleted(id, by, at, json.required("target", entryId))
            "series.created" -> {
                val series = json.required("series", ::obj)
                Event.SeriesCreated(
                    id,
                    by,
                    at,
                    series.required("id", seriesId),
                    series.required("from", date),
                    series.required("segment", segment),
                )
            }
            "series.amended" ->
                Event.SeriesAmended(
                    id,
                    by,
                    at,
                    json.required("target", seriesId),
                    json.required("from", date),
                    json.required("segment", segment),
                )
            "series.ended" ->
                Event.SeriesEnded(
                    id,
                    by,
                    at,
                    json.required("target", seriesId),
                    json.required("from", date),
                )
            "series.deleted" -> Event.SeriesDeleted(id, by, at, json.required("target", seriesId))
            "occurrence.confirmed" ->
                Event.OccurrenceConfirmed(
                    id,
                    by,
                    at,
                    json.required("series", seriesId),
                    json.required("due", date),
                )
            "occurrence.deleted" ->
                Event.OccurrenceDeleted(
                    id,
                    by,
                    at,
                    json.required("series", seriesId),
                    json.required("due", date),
                )
            "occurrence.amended" -> {
                val entry = json.required("entry", ::obj)
                entry.forbid("id", "an occurrence")
                Event.OccurrenceAmended(
                    id,
                    by,
                    at,
                    json.required("series", seriesId),
                    json.required("due", date),
                    entry.required("date", date),
                    template(entry),
                )
            }
            else -> throw Unsupported("event type '$type'")
        }

    fun encode(event: Event): JsonObject = buildJsonObject {
        put("id", event.id.hex)
        put("type", event.type)
        put("by", event.by.hex)
        // Second precision, whatever the clock gave (format-spec 3.2).
        put("at", DateTimeFormatter.ISO_INSTANT.format(event.at.truncatedTo(ChronoUnit.SECONDS)))
        when (event) {
            is Event.GroupCreated -> put("group", detailsJson(event.group))
            is Event.GroupUpdated -> put("group", changesJson(event.group))
            is Event.MemberAdded ->
                put(
                    "member",
                    buildJsonObject {
                        put("id", event.member.id.hex)
                        put("name", event.member.name)
                    },
                )
            is Event.MemberRenamed -> {
                put("target", event.target.hex)
                put("name", event.name)
            }
            is Event.MemberRemoved -> put("target", event.target.hex)
            is Event.EntryCreated -> put("entry", entryJson(event.entry))
            is Event.EntryUpdated -> {
                put("target", event.target.hex)
                put("entry", changesJson(event.entry))
            }
            is Event.EntryDeleted -> put("target", event.target.hex)
            is Event.SeriesCreated ->
                put(
                    "series",
                    buildJsonObject {
                        put("id", event.series.hex)
                        put("from", event.from.toString())
                        put("segment", segmentJson(event.segment))
                    },
                )
            is Event.SeriesAmended -> {
                put("target", event.target.hex)
                put("from", event.from.toString())
                put("segment", segmentJson(event.segment))
            }
            is Event.SeriesEnded -> {
                put("target", event.target.hex)
                put("from", event.from.toString())
            }
            is Event.SeriesDeleted -> put("target", event.target.hex)
            is Event.OccurrenceConfirmed -> {
                put("series", event.series.hex)
                put("due", event.due.toString())
            }
            is Event.OccurrenceDeleted -> {
                put("series", event.series.hex)
                put("due", event.due.toString())
            }
            is Event.OccurrenceAmended -> {
                put("series", event.series.hex)
                put("due", event.due.toString())
                put("entry", templateJson(event.entry, date = event.date))
            }
        }
    }
}

// --- Primitives (format-spec 3) ------------------------------------------------------------------

private val eventId: Decoder<EventId> = parsed(EventId::parse)
private val memberId: Decoder<MemberId> = parsed(MemberId::parse)
private val entryId: Decoder<EntryId> = parsed(EntryId::parse)
private val seriesId: Decoder<SeriesId> = parsed(SeriesId::parse)
private val groupId: Decoder<GroupId> = parsed(GroupId::parse)
private val date: Decoder<EntryDate> = parsed(EntryDate::parse)
private val colour: Decoder<Colour> = parsed(Colour::parse)
private val attachment: Decoder<AttachmentName> = parsed(AttachmentName::parse)
private val amount: Decoder<Amount> = parsed(Amount::parse)

private val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")

private val instant: Decoder<Instant> = parsed {
    require(INSTANT.matches(it))
    Instant.parse(it)
}

/** An entry's or a template's amount, which carries no sign of its own (format-spec 3.3). */
private val positive: Decoder<Amount> = { value, name ->
    amount(value, name).also { if (!it.isPositive) throw Malformed("'$name' is not positive") }
}

private val scale: Decoder<Scale> = { value, name ->
    val digits = integer(value, name)
    if (digits !in 0..Amount.UNIT_DIGITS) throw Malformed("'$name' is not 0 to 8")
    Scale.of(digits.toInt())
}

private val members: Decoder<List<MemberId>> = { value, name ->
    array(value, name).map { memberId(it, name) }
}

private val attachments: Decoder<List<AttachmentName>> = { value, name ->
    array(value, name).map { attachment(it, name) }
}

private fun <T> enumeration(vararg values: Pair<String, T>): Decoder<T> = { value, name ->
    val content = text(value, name)
    values.firstOrNull { it.first == content }?.second
        ?: throw Malformed("'$name' is none of ${values.joinToString { it.first }}")
}

private val KINDS =
    listOf("expense" to Kind.EXPENSE, "income" to Kind.INCOME, "transfer" to Kind.TRANSFER)
private val kind: Decoder<Kind> = enumeration(*KINDS.toTypedArray())

private val MODES = listOf("automatic" to Mode.AUTOMATIC, "suggested" to Mode.SUGGESTED)
private val mode: Decoder<Mode> = enumeration(*MODES.toTypedArray())

private val DAYS = listOf("mo", "tu", "we", "th", "fr", "sa", "su").zip(DayOfWeek.entries)
private val day: Decoder<DayOfWeek> = enumeration(*DAYS.toTypedArray())

private fun <T> wire(pairs: List<Pair<String, T>>, value: T): String =
    pairs.first { it.second == value }.first

// --- Group and member ----------------------------------------------------------------------------

private val groupDetails: Decoder<GroupDetails> = { value, name ->
    val json = obj(value, name)
    json.forbid("defaultParticipants", "group.created")
    GroupDetails(
        json.required("id", groupId),
        json.required("scale", scale),
        json.required("name", ::text),
        json.optional("description", ::text),
        json.optional("emoji", ::text),
        json.optional("colour", colour),
    )
}

private val groupChanges: Decoder<GroupChanges> = { value, name ->
    val json = obj(value, name)
    json.forbid("id", "an update")
    GroupChanges(
        json.change("scale", scale),
        json.change("name", ::text),
        json.clearable("description", ::text),
        json.clearable("emoji", ::text),
        json.clearable("colour", colour),
        json.clearable("defaultParticipants", members),
    )
}

private val member: Decoder<Member> = { value, name ->
    val json = obj(value, name)
    Member(json.required("id", memberId), json.required("name", ::text))
}

private fun detailsJson(group: GroupDetails) = buildJsonObject {
    put("id", group.id.hex)
    put("scale", group.scale.digits)
    put("name", group.name)
    group.description?.let { put("description", it) }
    group.emoji?.let { put("emoji", it) }
    group.colour?.let { put("colour", it.text) }
}

private fun changesJson(group: GroupChanges) = buildJsonObject {
    put("scale", group.scale) { JsonPrimitive(it.digits) }
    put("name", group.name) { JsonPrimitive(it) }
    put("description", group.description) { JsonPrimitive(it) }
    put("emoji", group.emoji) { JsonPrimitive(it) }
    put("colour", group.colour) { JsonPrimitive(it.text) }
    put("defaultParticipants", group.defaultParticipants) { ids ->
        buildJsonArray { ids.forEach { add(it.hex) } }
    }
}

/** Only what changed (format-spec 4.2): nothing for [Change.Unchanged], `null` to clear. */
private fun <T> JsonObjectBuilder.put(
    name: String,
    change: Change<T>,
    encode: (T & Any) -> JsonElement,
) {
    if (change is Change.To) put(name, change.value?.let(encode) ?: JsonNull)
}

// --- Entries and sides ---------------------------------------------------------------------------

private val side: Decoder<Side> = { value, name ->
    val json = obj(value, name)
    when (val scheme = json.required("scheme", ::text)) {
        "equal" -> Side.Equal(json.required("input", members))
        "shares" -> Side.Shares(json.required("input", keyed(::integer)))
        "adjustments" -> Side.Adjustments(json.required("input", keyed(amount)))
        "absolute" -> Side.Absolute(json.required("input", keyed(amount)))
        else -> throw Unsupported("scheme '$scheme'")
    }
}

/** An object keyed by member identifier, whose keys [restricted] has already kept distinct. */
private fun <T> keyed(decode: Decoder<T>): Decoder<Map<MemberId, T>> = { value, name ->
    obj(value, name).entries.associate { (key, input) ->
        memberId(JsonPrimitive(key), name) to decode(input, name)
    }
}

private val entry: Decoder<Entry> = { value, name ->
    val json = obj(value, name)
    template(json).on(json.required("id", entryId), json.required("date", date))
}

private val entryChanges: Decoder<EntryChanges> = { value, name ->
    val json = obj(value, name)
    json.forbid("id", "an update")
    EntryChanges(
        json.change("kind", kind),
        json.change("description", ::text),
        json.change("date", date),
        json.change("amount", positive),
        json.change("payers", side),
        json.change("beneficiaries", side),
        json.clearable("note", ::text),
        json.clearable("attachments", attachments),
    )
}

/** The fields an entry shares with a template; whether `id` and `date` belong is the caller's. */
private fun template(json: JsonObject) =
    Template(
        json.required("kind", kind),
        json.required("description", ::text),
        json.required("amount", positive),
        json.required("payers", side),
        json.required("beneficiaries", side),
        json.optional("note", ::text),
        json.optional("attachments", attachments),
    )

private fun entryJson(entry: Entry) =
    templateJson(
        Template(
            entry.kind,
            entry.description,
            entry.amount,
            entry.payers,
            entry.beneficiaries,
            entry.note,
            entry.attachments,
        ),
        id = entry.id,
        date = entry.date,
    )

private fun templateJson(template: Template, id: EntryId? = null, date: EntryDate? = null) =
    buildJsonObject {
        id?.let { put("id", it.hex) }
        put("kind", wire(KINDS, template.kind))
        put("description", template.description)
        date?.let { put("date", it.toString()) }
        put("amount", template.amount.toString())
        put("payers", sideJson(template.payers))
        put("beneficiaries", sideJson(template.beneficiaries))
        template.note?.let { put("note", it) }
        template.attachments?.let { names ->
            put("attachments", buildJsonArray { names.forEach { add(it.hex) } })
        }
    }

private fun changesJson(entry: EntryChanges) = buildJsonObject {
    put("kind", entry.kind) { JsonPrimitive(wire(KINDS, it)) }
    put("description", entry.description) { JsonPrimitive(it) }
    put("date", entry.date) { JsonPrimitive(it.toString()) }
    put("amount", entry.amount) { JsonPrimitive(it.toString()) }
    put("payers", entry.payers) { sideJson(it) }
    put("beneficiaries", entry.beneficiaries) { sideJson(it) }
    put("note", entry.note) { JsonPrimitive(it) }
    put("attachments", entry.attachments) { names ->
        buildJsonArray { names.forEach { add(it.hex) } }
    }
}

private fun sideJson(side: Side) = buildJsonObject {
    put("scheme", side.scheme.name.lowercase())
    when (side) {
        is Side.Equal -> put("input", buildJsonArray { side.members.forEach { add(it.hex) } })
        is Side.Shares ->
            put("input", buildJsonObject { side.input.forEach { (id, n) -> put(id.hex, n) } })
        is Side.Adjustments ->
            put(
                "input",
                buildJsonObject { side.input.forEach { (id, a) -> put(id.hex, a.toString()) } },
            )
        is Side.Absolute ->
            put(
                "input",
                buildJsonObject { side.input.forEach { (id, a) -> put(id.hex, a.toString()) } },
            )
    }
}

// --- Recurrence ----------------------------------------------------------------------------------

private val INTERVALS = listOf("everyDays", "monthlyOn", "weeklyOn")

private val interval: Decoder<Interval> = { value, name ->
    val json = obj(value, name)
    val known = INTERVALS.filter { it in json }
    if (known.isEmpty()) throw Unsupported("an interval naming none of $INTERVALS")
    if (known.size > 1) throw Malformed("'$name' is $known at once")
    when (known.single()) {
        "everyDays" -> {
            val days = json.required("everyDays", ::integer)
            if (days < 1) throw Malformed("'everyDays' is not positive")
            // Past Int.MAX_VALUE days the second date is millions of years out: the same series.
            Interval.EveryDays(minOf(days, Int.MAX_VALUE.toLong()).toInt())
        }
        "monthlyOn" -> {
            val days = json.required("monthlyOn", distinct(::integer))
            if (days.any { it !in 1..31 }) throw Malformed("'monthlyOn' is outside 1 to 31")
            Interval.MonthlyOn(days.map { it.toInt() }.toSet())
        }
        else -> Interval.WeeklyOn(json.required("weeklyOn", distinct(day)).toSet())
    }
}

private val segment: Decoder<Segment> = { value, name ->
    val json = obj(value, name)
    Segment(
        json.required("interval", interval),
        json.required("mode", mode),
        json.required("template", ::obj).let { fields ->
            fields.forbid("id", "a template")
            fields.forbid("date", "a template")
            template(fields)
        },
    )
}

private fun segmentJson(segment: Segment) = buildJsonObject {
    put(
        "interval",
        buildJsonObject {
            when (val interval = segment.interval) {
                is Interval.EveryDays -> put("everyDays", interval.days)
                is Interval.MonthlyOn ->
                    put("monthlyOn", buildJsonArray { interval.days.sorted().forEach { add(it) } })
                is Interval.WeeklyOn ->
                    put(
                        "weeklyOn",
                        buildJsonArray { interval.days.sorted().forEach { add(wire(DAYS, it)) } },
                    )
            }
        },
    )
    put("mode", wire(MODES, segment.mode))
    put("template", templateJson(segment.template))
}
