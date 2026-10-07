package eu.teele.neugesplitter.domain.history

import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import java.time.Instant

/** What one event did to an entry; not `Change`, which is a field of an update payload. */
enum class Revision {
    CREATED,
    EDITED,
    DELETED,
    /** An update after a deletion, which brings the entry back (event-model 4). */
    RESTORED,
}

/**
 * One step in the life of an entry (HI-3).
 *
 * @property before the entry as it was just before, null for [Revision.CREATED].
 * @property after the entry as it is just after; for [Revision.DELETED] the same as [before], the
 *   content at deletion.
 */
data class Version(
    val change: Revision,
    val by: MemberId,
    val at: Instant,
    val before: Entry?,
    val after: Entry,
)

/**
 * Every event of [events] that creates, edits, deletes or restores [entry], oldest first (HI-3).
 * The log is folded through [Group], so that what counts as a restoration is what replay says.
 * Empty if the log has no such entry.
 */
fun versions(events: List<Event>, entry: EntryId): List<Version> {
    val first = events.firstOrNull() ?: return emptyList()
    var group = Group.replay(listOf(first))
    val versions = mutableListOf<Version>()
    for (event in events.drop(1)) {
        val before = group.entries[entry] ?: group.deleted[entry]
        val wasDeleted = entry in group.deleted
        group += event
        val change =
            when {
                event is Event.EntryCreated && event.entry.id == entry -> Revision.CREATED
                event is Event.EntryUpdated && event.target == entry ->
                    if (wasDeleted) Revision.RESTORED else Revision.EDITED
                event is Event.EntryDeleted && event.target == entry -> Revision.DELETED
                else -> continue
            }
        val after = group.entries[entry] ?: group.deleted.getValue(entry)
        versions += Version(change, event.by, event.at, before, after)
    }
    return versions
}
