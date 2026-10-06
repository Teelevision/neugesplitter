package eu.teele.neugesplitter.domain.event

import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.AttachmentName
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import java.time.Instant

/**
 * The `group` payload of `group.created` (format-spec 4.2).
 *
 * No `defaultParticipants`: it names members, and this event runs before there are any.
 */
data class GroupDetails(
    val id: GroupId,
    val scale: Scale,
    val name: String,
    val description: String? = null,
    val emoji: String? = null,
    val colour: Colour? = null,
)

/** The `group` payload of `group.updated`: the same fields, naming only what changed. */
data class GroupChanges(
    val scale: Change<Scale> = Change.Unchanged,
    val name: Change<String> = Change.Unchanged,
    val description: Change<String?> = Change.Unchanged,
    val emoji: Change<String?> = Change.Unchanged,
    val colour: Change<Colour?> = Change.Unchanged,
    val defaultParticipants: Change<List<MemberId>?> = Change.Unchanged,
)

/** The `entry` payload of `entry.updated`: the same fields, naming only what changed. */
data class EntryChanges(
    val kind: Change<Kind> = Change.Unchanged,
    val description: Change<String> = Change.Unchanged,
    val date: Change<EntryDate> = Change.Unchanged,
    val amount: Change<Amount> = Change.Unchanged,
    val payers: Change<Side> = Change.Unchanged,
    val beneficiaries: Change<Side> = Change.Unchanged,
    val note: Change<String?> = Change.Unchanged,
    val attachments: Change<List<AttachmentName>?> = Change.Unchanged,
)

/**
 * An event of format-spec 4.2, without its encoding.
 *
 * The event types are fixed from outside the model and cannot be renamed by a refactor, so the
 * hierarchy is sealed here and the mapping to JSON lives in `:format` (development.md 1). `at` is
 * recorded and never consulted: order is the order of the log (event-model 1).
 *
 * Recurrence is not here yet; the series and occurrence types arrive with it.
 */
sealed interface Event {
    val id: EventId
    val by: MemberId
    val at: Instant

    /** The `type` of format-spec 4.2, which a refactor may not rename. */
    val type: String

    data class GroupCreated(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val group: GroupDetails,
    ) : Event {
        override val type = "group.created"
    }

    data class GroupUpdated(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val group: GroupChanges,
    ) : Event {
        override val type = "group.updated"
    }

    data class MemberAdded(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val member: Member,
    ) : Event {
        override val type = "member.added"
    }

    data class MemberRenamed(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: MemberId,
        val name: String,
    ) : Event {
        override val type = "member.renamed"
    }

    data class MemberRemoved(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: MemberId,
    ) : Event {
        override val type = "member.removed"
    }

    data class EntryCreated(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val entry: Entry,
    ) : Event {
        override val type = "entry.created"
    }

    data class EntryUpdated(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: EntryId,
        val entry: EntryChanges,
    ) : Event {
        override val type = "entry.updated"
    }

    data class EntryDeleted(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: EntryId,
    ) : Event {
        override val type = "entry.deleted"
    }
}
