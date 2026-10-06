package eu.teele.neugesplitter.domain

import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.event.GroupDetails
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import java.time.Instant

val GROUP: GroupId = GroupId.parse("%032x".format(0xc0a1))

val AT: Instant = Instant.parse("2026-08-31T18:04:11Z")

val TODAY: EntryDate = EntryDate.parse("2026-08-31")

fun entryId(n: Int): EntryId = EntryId.parse("%032x".format(0xe000 + n))

fun entry(
    n: Int,
    amount: Amount,
    payers: Side,
    beneficiaries: Side,
    kind: Kind = Kind.EXPENSE,
    description: String = "Entry $n",
): Entry = Entry(entryId(n), kind, description, TODAY, amount, payers, beneficiaries)

/**
 * A log in the order it was written, with event identifiers handed in rather than drawn, because
 * the domain may not draw one itself (development.md 1).
 */
class Log(private val by: MemberId = ANNA) {

    private val events = mutableListOf<Event>()
    private var serial = 0

    private fun id(): EventId = EventId.parse("%032x".format(++serial))

    fun create(name: String = "Test", scale: Scale = Scale.DEFAULT) = apply {
        events += Event.GroupCreated(id(), by, AT, GroupDetails(GROUP, scale, name))
    }

    fun update(changes: GroupChanges) = apply {
        events += Event.GroupUpdated(id(), by, AT, changes)
    }

    fun add(member: MemberId, name: String) = apply {
        events += Event.MemberAdded(id(), by, AT, Member(member, name))
    }

    fun rename(target: MemberId, name: String) = apply {
        events += Event.MemberRenamed(id(), by, AT, target, name)
    }

    fun remove(target: MemberId) = apply { events += Event.MemberRemoved(id(), by, AT, target) }

    fun record(entry: Entry) = apply { events += Event.EntryCreated(id(), by, AT, entry) }

    fun update(target: EntryId, changes: EntryChanges) = apply {
        events += Event.EntryUpdated(id(), by, AT, target, changes)
    }

    fun delete(target: EntryId) = apply { events += Event.EntryDeleted(id(), by, AT, target) }

    fun events(): List<Event> = events.toList()

    fun replay(): Group = Group.replay(events)
}
