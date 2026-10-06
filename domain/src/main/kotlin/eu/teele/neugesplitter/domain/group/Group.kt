package eu.teele.neugesplitter.domain.group

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.balance.Settlement
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.event.or
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale

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
                copy(entries = entries + (event.entry.id to checked(event.entry)))
            }

            is Event.EntryUpdated -> {
                val entry = checked(known(event.target).merged(event.entry))
                copy(entries = entries + (entry.id to entry), deleted = deleted - entry.id)
            }

            is Event.EntryDeleted -> {
                val entry = known(event.target)
                copy(entries = entries - entry.id, deleted = deleted + (entry.id to entry))
            }
        }

    /** Every member's exact balance in 10⁻⁸ units, zero included (format-spec 7.1). */
    fun balances(): Map<MemberId, Amount> {
        val totals = members.keys.associateWithTo(mutableMapOf()) { Amount.ZERO }
        for (entry in entries.values) {
            for ((member, contribution) in entry.contributions()) {
                totals[member] = (totals[member] ?: Amount.ZERO) + contribution
            }
        }
        return totals
    }

    /** The balances as they are shown and settled, at this group's scale (BA-2). */
    fun settlement(): Map<MemberId, Amount> = Settlement.rounded(balances(), scale)

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

    /** An entry is checked when it enters the state, so that a stored one can always be split. */
    private fun checked(entry: Entry): Entry {
        for (member in entry.payers.members + entry.beneficiaries.members) {
            if (member !in members) {
                throw Malformed("entry ${entry.id} names $member, who is not a member of group $id")
            }
        }
        entry.contributions()
        return entry
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
    }
}
