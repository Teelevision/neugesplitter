package eu.teele.neugesplitter.format

import eu.teele.neugesplitter.domain.POOL
import eu.teele.neugesplitter.domain.arbAmount
import eu.teele.neugesplitter.domain.arbDate
import eu.teele.neugesplitter.domain.arbScale
import eu.teele.neugesplitter.domain.arbSegment
import eu.teele.neugesplitter.domain.arbSideFor
import eu.teele.neugesplitter.domain.arbTemplate
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.event.GroupDetails
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.AttachmentName
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.SeriesId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import java.time.Instant

/** Pieces chosen to break an encoder: quotes, escapes, controls, and every UTF-8 length. */
private val PIECES =
    listOf(
        "a",
        "Z",
        "0",
        " ",
        "\"",
        "\\",
        "/",
        "\n",
        "\t",
        "\u0000",
        "\u001f",
        "\u007f",
        "é",
        "€",
        "\u2028",
        "🧾",
        "\uFEFF",
    )

val arbText: Arb<String> = Arb.list(Arb.element(PIECES), 0..12).map { it.joinToString("") }

private fun hexId(range: LongRange = 1L..Long.MAX_VALUE): Arb<String> =
    Arb.long(range).map { "%032x".format(it) }

/** Whole seconds, because the wire's `at` has no fraction. */
private val arbInstant: Arb<Instant> =
    Arb.long(0L..4_102_444_799L).map { Instant.ofEpochSecond(it) }

private val arbMember: Arb<MemberId> = Arb.element(POOL)

private val arbMembers: Arb<List<MemberId>> = arbitrary {
    val members = POOL.filter { Arb.boolean().bind() }.ifEmpty { POOL.take(1) }
    if (Arb.boolean().bind()) members.reversed() else members
}

private val arbColour: Arb<Colour> =
    Arb.element("#336699", "#a0B1c2", "#000000", "#FFFFFF").map { Colour.parse(it) }

private val arbAttachments: Arb<List<AttachmentName>> =
    Arb.list(Arb.long(0L..Long.MAX_VALUE).map { AttachmentName.parse("%064x".format(it)) }, 0..3)
        .map { it.distinct() }

private fun <T> arbChange(value: Arb<T>): Arb<Change<T>> =
    Arb.choice(Arb.constant(Change.Unchanged), value.map { Change.To(it) })

private val arbEntryId: Arb<EntryId> = hexId().map { EntryId.parse(it) }

private val arbSeriesId: Arb<SeriesId> = hexId().map { SeriesId.parse(it) }

val arbGroupChanges: Arb<GroupChanges> = arbitrary {
    GroupChanges(
        arbChange(arbScale).bind(),
        arbChange(arbText).bind(),
        arbChange(arbText.orNull()).bind(),
        arbChange(arbText.orNull()).bind(),
        arbChange(arbColour.orNull()).bind(),
        arbChange(arbMembers.orNull()).bind(),
    )
}

val arbEntryChanges: Arb<EntryChanges> = arbitrary {
    val amount = arbAmount.bind()
    EntryChanges(
        arbChange(Arb.enum<Kind>()).bind(),
        arbChange(arbText).bind(),
        arbChange(arbDate).bind(),
        arbChange(Arb.constant(amount)).bind(),
        arbChange(arbSideFor(amount)).bind(),
        arbChange(arbSideFor(amount)).bind(),
        arbChange(arbText.orNull()).bind(),
        arbChange(arbAttachments.orNull()).bind(),
    )
}

/** A template with free text, a note and attachments, which the domain's generator leaves out. */
private val arbFullTemplate = arbitrary {
    arbTemplate
        .bind()
        .copy(
            description = arbText.bind(),
            note = arbText.orNull().bind(),
            attachments = arbAttachments.orNull().bind(),
        )
}

/** Any event of format-spec 4.2, with no regard for whether a log of them would replay. */
val arbEvent: Arb<Event> = arbitrary {
    val id = EventId.parse(hexId().bind())
    val by = arbMember.bind()
    val at = arbInstant.bind()
    when (Arb.int(0..15).bind()) {
        0 ->
            Event.GroupCreated(
                id,
                by,
                at,
                GroupDetails(
                    GroupId.parse(hexId().bind()),
                    arbScale.bind(),
                    arbText.bind(),
                    arbText.orNull().bind(),
                    arbText.orNull().bind(),
                    arbColour.orNull().bind(),
                ),
            )
        1 -> Event.GroupUpdated(id, by, at, arbGroupChanges.bind())
        2 -> Event.MemberAdded(id, by, at, Member(arbMember.bind(), arbText.bind()))
        3 -> Event.MemberRenamed(id, by, at, arbMember.bind(), arbText.bind())
        4 -> Event.MemberRemoved(id, by, at, arbMember.bind())
        5 ->
            Event.EntryCreated(
                id,
                by,
                at,
                arbFullTemplate.bind().on(arbEntryId.bind(), arbDate.bind()),
            )
        6 -> Event.EntryUpdated(id, by, at, arbEntryId.bind(), arbEntryChanges.bind())
        7 -> Event.EntryDeleted(id, by, at, arbEntryId.bind())
        8 ->
            Event.SeriesCreated(
                id,
                by,
                at,
                arbSeriesId.bind(),
                arbDate.bind(),
                arbSegment.bind().copy(template = arbFullTemplate.bind()),
            )
        9 -> Event.SeriesAmended(id, by, at, arbSeriesId.bind(), arbDate.bind(), arbSegment.bind())
        10 ->
            Event.SeriesStopped(
                id,
                by,
                at,
                arbSeriesId.bind(),
                arbDate.bind(),
                arbDate.orNull().bind(),
            )
        11 ->
            Event.SeriesContinued(
                id,
                by,
                at,
                arbSeriesId.bind(),
                arbDate.bind(),
                arbDate.orNull().bind(),
            )
        12 -> Event.SeriesDeleted(id, by, at, arbSeriesId.bind())
        13 -> Event.OccurrenceConfirmed(id, by, at, arbSeriesId.bind(), arbDate.bind())
        14 -> Event.OccurrenceDeleted(id, by, at, arbSeriesId.bind(), arbDate.bind())
        else ->
            Event.OccurrenceAmended(
                id,
                by,
                at,
                arbSeriesId.bind(),
                arbDate.bind(),
                arbDate.bind(),
                arbFullTemplate.bind(),
            )
    }
}
