package eu.teele.neugesplitter.domain.group

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.balance.Settlement
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.event.GroupDetails
import eu.teele.neugesplitter.domain.event.or
import eu.teele.neugesplitter.domain.recurrence.Decision
import eu.teele.neugesplitter.domain.recurrence.Occurrence
import eu.teele.neugesplitter.domain.recurrence.Series
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import java.time.Instant

/**
 * A group, which is the only aggregate: one group is one consistency boundary and nothing refers
 * across groups (development.md 1, GR-1, ID-2).
 *
 * This is the state a log produces, not the log itself. Nothing is ever deleted from the log, so
 * removal is a flag and a deleted entry is kept here too — an `entry.updated` after a deletion
 * resurrects it, which is how HI-9 works without a second mechanism (event-model 4).
 */
data class Group(
    val id: GroupId,
    val scale: Scale,
    val name: String,
    val description: String? = null,
    val emoji: String? = null,
    val colour: Colour? = null,
    /** Null is not "none" but "everyone", and is where every group starts (format-spec 4.2). */
    val defaultParticipants: List<MemberId>? = null,
    val members: Map<MemberId, Member> = emptyMap(),
    val removed: Set<MemberId> = emptySet(),
    val entries: Map<EntryId, Entry> = emptyMap(),
    val deleted: Map<EntryId, Entry> = emptyMap(),
    /** Every entry ever created, deleted ones included, in the order of creation (HI-4). */
    val authors: Map<EntryId, Authorship> = emptyMap(),
    /** Deleted ones included, because an occurrence amended after the deletion still stands. */
    val series: Map<SeriesId, Series> = emptyMap(),
) {

    /**
     * This group with [event] applied (format-spec 4.2).
     *
     * @throws Malformed if the event contradicts the state before it.
     */
    operator fun plus(event: Event): Group =
        when (event) {
            is Event.GroupCreated ->
                throw Malformed("group.created is the first event of a group and appears once")

            is Event.GroupUpdated -> merged(event.group)

            is Event.MemberAdded -> {
                if (event.member.id in members) {
                    throw Malformed("member ${event.member.id} is added twice")
                }
                copy(members = members + (event.member.id to event.member))
            }

            is Event.MemberRenamed -> {
                val member = known(event.target)
                copy(members = members + (event.target to member.copy(name = event.name)))
            }

            // Removing twice is not a contradiction: two installations may do it independently.
            is Event.MemberRemoved -> {
                known(event.target)
                copy(removed = removed + event.target)
            }

            is Event.EntryCreated -> {
                if (event.entry.id in entries || event.entry.id in deleted) {
                    throw Malformed("entry ${event.entry.id} is created twice")
                }
                copy(
                    entries = entries + (event.entry.id to checked(event.entry)),
                    authors = authors + (event.entry.id to Authorship(event.by)),
                )
            }

            is Event.EntryUpdated -> {
                val entry = checked(known(event.target).merged(event.entry))
                copy(
                    entries = entries + (entry.id to entry),
                    deleted = deleted - entry.id,
                    authors = authors + (entry.id to authors.getValue(entry.id).copy(edited = true)),
                )
            }

            is Event.EntryDeleted -> {
                val entry = known(event.target)
                copy(entries = entries - entry.id, deleted = deleted + (entry.id to entry))
            }

            is Event.SeriesCreated -> {
                if (event.series in series) {
                    throw Malformed("series ${event.series} is created twice")
                }
                checked(event.segment.template, "series ${event.series}")
                val created = Series(event.series, listOf(event.from to event.segment))
                copy(series = series + (event.series to created))
            }

            is Event.SeriesAmended -> {
                checked(event.segment.template, "series ${event.target}")
                revised(event.target) { it.from(event.from, event.segment).restored() }
            }

            is Event.SeriesEnded -> revised(event.target) { it.from(event.from, null) }

            is Event.SeriesDeleted -> revised(event.target) { it.removed() }

            is Event.OccurrenceConfirmed ->
                revised(event.series) { it.decided(event.due, Decision.Confirmed) }

            is Event.OccurrenceDeleted ->
                revised(event.series) { it.decided(event.due, Decision.Deleted) }

            is Event.OccurrenceAmended -> {
                checked(event.entry, "occurrence ${event.due} of series ${event.series}")
                revised(event.series) {
                    it.decided(event.due, Decision.Amended(event.date, event.entry))
                }
            }
        }

    /**
     * Every occurrence due by [today], the installation's local date, pending ones included
     * (RE-14).
     */
    fun occurrences(today: EntryDate): List<Occurrence> =
        series.values.flatMap { it.occurrences(today) }

    /** Every member's exact balance in 10⁻⁸ units, zero included (format-spec 7.1). */
    fun balances(today: EntryDate): Map<MemberId, Amount> {
        val totals = members.keys.associateWithTo(mutableMapOf()) { Amount.ZERO }
        val due = occurrences(today).filterNot { it.pending }.map { it.entry }
        for (entry in entries.values + due) {
            for ((member, contribution) in entry.contributions()) {
                totals[member] = (totals[member] ?: Amount.ZERO) + contribution
            }
        }
        return totals
    }

    /** The balances as they are shown and settled, at this group's scale (BA-2). */
    fun settlement(today: EntryDate): Map<MemberId, Amount> =
        Settlement.rounded(balances(today), scale)

    /** Who a new entry starts out with: everyone, unless the group says otherwise (SP-8, GR-8). */
    fun participants(): List<MemberId> =
        (defaultParticipants ?: members.keys.toList()).filterNot { it in removed }

    private fun merged(changes: GroupChanges) =
        copy(
            scale = changes.scale.or(scale),
            name = changes.name.or(name),
            description = changes.description.or(description),
            emoji = changes.emoji.or(emoji),
            colour = changes.colour.or(colour),
            defaultParticipants =
                changes.defaultParticipants.or(defaultParticipants)?.onEach { known(it) },
        )

    private fun Entry.merged(changes: EntryChanges) =
        copy(
            kind = changes.kind.or(kind),
            description = changes.description.or(description),
            date = changes.date.or(date),
            amount = changes.amount.or(amount),
            payers = changes.payers.or(payers),
            beneficiaries = changes.beneficiaries.or(beneficiaries),
            note = changes.note.or(note),
            attachments = changes.attachments.or(attachments),
        )

    private fun known(target: MemberId): Member =
        members[target] ?: throw Malformed("no member $target in group $id")

    private fun known(target: EntryId): Entry =
        entries[target] ?: deleted[target] ?: throw Malformed("no entry $target in group $id")

    private fun revised(target: SeriesId, change: (Series) -> Series): Group {
        val known = series[target] ?: throw Malformed("no series $target in group $id")
        return copy(series = series + (target to change(known)))
    }

    /** An entry is checked when it enters the state, so that a stored one can always be split. */
    private fun checked(entry: Entry): Entry {
        requireSplittable("entry ${entry.id}", entry.amount, entry.payers, entry.beneficiaries)
        return entry
    }

    private fun checked(template: Template, what: String) =
        requireSplittable(what, template.amount, template.payers, template.beneficiaries)

    private fun requireSplittable(what: String, amount: Amount, payers: Side, beneficiaries: Side) {
        for (member in payers.members + beneficiaries.members) {
            if (member !in members) {
                throw Malformed("$what names $member, who is not a member of group $id")
            }
        }
        payers.parts(amount)
        beneficiaries.parts(amount)
    }

    companion object {
        /**
         * The group a log produces, in log order (TR-19).
         *
         * @throws Malformed if the log does not begin with `group.created`, or if any event
         *   contradicts the state before it.
         */
        fun replay(events: List<Event>): Group {
            val created = events.firstOrNull() ?: throw Malformed("a group's log cannot be empty")
            if (created !is Event.GroupCreated) {
                throw Malformed("a group's log begins with group.created, not ${created.type}")
            }
            val group =
                with(created.group) {
                    Group(
                        id = id,
                        scale = scale,
                        name = name,
                        description = description,
                        emoji = emoji,
                        colour = colour,
                    )
                }
            return events.drop(1).fold(group) { state, event -> state + event }
        }

        /**
         * The events that create a group: its `group.created`, then a `member.added` for each of
         * [members] in order. The first member is the person creating it, who writes all of them
         * (GR-1, GR-2, ID-2). Decimal places start at two and are not asked for (AM-7).
         *
         * @param ids draws a fresh event identifier each time it is called.
         * @throws IllegalArgumentException if there are no members, two share an identifier, or the
         *   group or a member has a blank name.
         */
        fun create(
            id: GroupId,
            name: String,
            description: String?,
            emoji: String?,
            colour: Colour?,
            members: List<Member>,
            at: Instant,
            ids: () -> EventId,
        ): List<Event> {
            require(name.isNotBlank()) { "a group has a name (GR-2)" }
            require(members.isNotEmpty()) { "a group is created by one of its members (ID-2)" }
            require(members.all { it.name.isNotBlank() }) { "a member has a name (ID-2)" }
            require(members.distinctBy { it.id }.size == members.size) { "a member is added twice" }
            val by = members.first().id
            val details = GroupDetails(id, Scale.DEFAULT, name, description, emoji, colour)
            return listOf(Event.GroupCreated(ids(), by, at, details)) +
                members.map { Event.MemberAdded(ids(), by, at, it) }
        }
    }
}
