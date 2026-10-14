package eu.teele.neugesplitter.domain.event

import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.todo.TodoMove
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
import eu.teele.neugesplitter.domain.value.TodoId
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
) {
    /** True if this names no field, and so would change nothing. */
    val isEmpty: Boolean
        get() = this == EntryChanges()

    companion object {
        /** The fields in which [after] differs from [before], which must be the same entry. */
        fun between(before: Entry, after: Entry): EntryChanges {
            require(before.id == after.id) { "${before.id} and ${after.id} are different entries" }
            fun <T> change(old: T, new: T): Change<T> =
                if (old == new) Change.Unchanged else Change.To(new)
            return EntryChanges(
                kind = change(before.kind, after.kind),
                description = change(before.description, after.description),
                date = change(before.date, after.date),
                amount = change(before.amount, after.amount),
                payers = change(before.payers, after.payers),
                beneficiaries = change(before.beneficiaries, after.beneficiaries),
                note = change(before.note, after.note),
                attachments = change(before.attachments, after.attachments),
            )
        }
    }
}

/** The `todo` payload of `todo.updated`: the same fields, naming only what changed. */
data class TodoChanges(
    val description: Change<String> = Change.Unchanged,
    val due: Change<EntryDate?> = Change.Unchanged,
    val assignees: Change<List<MemberId>?> = Change.Unchanged,
) {
    /** True if this names no field, and so would change nothing. */
    val isEmpty: Boolean
        get() = this == TodoChanges()

    companion object {
        /** The fields in which [after] differs from [before]. */
        fun between(before: Todo, after: Todo): TodoChanges {
            fun <T> change(old: T, new: T): Change<T> =
                if (old == new) Change.Unchanged else Change.To(new)
            return TodoChanges(
                description = change(before.description, after.description),
                due = change(before.due, after.due),
                assignees =
                    if (before.assignees == after.assignees) Change.Unchanged
                    else Change.To(after.assignees.ifEmpty { null }),
            )
        }
    }
}

/**
 * An event of format-spec 4.2, without its encoding.
 *
 * The event types are fixed from outside the model and cannot be renamed by a refactor, so the
 * hierarchy is sealed here and the mapping to JSON lives in `:format` (development.md 1). `at` is
 * recorded and never consulted: order is the order of the log (event-model 1).
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

    data class SeriesCreated(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val series: SeriesId,
        val from: EntryDate,
        val segment: Segment,
    ) : Event {
        override val type = "series.created"
    }

    data class SeriesAmended(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: SeriesId,
        val from: EntryDate,
        val segment: Segment,
    ) : Event {
        override val type = "series.amended"
    }

    /** A pause until [until], or a stop for good without one. */
    data class SeriesStopped(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: SeriesId,
        val from: EntryDate,
        val until: EntryDate? = null,
    ) : Event {
        override val type = "series.stopped"
    }

    /** Takes [from] to [until], or on for good without one, back out of the series' stops. */
    data class SeriesContinued(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: SeriesId,
        val from: EntryDate,
        val until: EntryDate? = null,
    ) : Event {
        override val type = "series.continued"
    }

    data class SeriesDeleted(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: SeriesId,
    ) : Event {
        override val type = "series.deleted"
    }

    data class OccurrenceConfirmed(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val series: SeriesId,
        val due: EntryDate,
    ) : Event {
        override val type = "occurrence.confirmed"
    }

    data class OccurrenceDeleted(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val series: SeriesId,
        val due: EntryDate,
    ) : Event {
        override val type = "occurrence.deleted"
    }

    /** The wire's `entry` is [date] and [entry] together: an entry with no `id`. */
    data class OccurrenceAmended(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val series: SeriesId,
        val due: EntryDate,
        val date: EntryDate,
        val entry: Template,
    ) : Event {
        override val type = "occurrence.amended"
    }

    /** Adds [todo] at the end of the list (TD-1). */
    data class TodoCreated(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val todo: Todo,
    ) : Event {
        override val type = "todo.created"
    }

    data class TodoUpdated(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: TodoId,
        val todo: TodoChanges,
    ) : Event {
        override val type = "todo.updated"
    }

    /** Applies [moves] one after the other, in their order (TD-3). */
    data class TodoMoved(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val moves: List<TodoMove>,
    ) : Event {
        override val type = "todo.moved"
    }

    /** Marks [targets] done (TD-5). */
    data class TodoCompleted(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val targets: List<TodoId>,
    ) : Event {
        override val type = "todo.completed"
    }

    /** Marks [targets] not done (TD-5). */
    data class TodoReopened(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val targets: List<TodoId>,
    ) : Event {
        override val type = "todo.reopened"
    }

    data class TodoDeleted(
        override val id: EventId,
        override val by: MemberId,
        override val at: Instant,
        val target: TodoId,
    ) : Event {
        override val type = "todo.deleted"
    }
}
