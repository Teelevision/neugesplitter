package eu.teele.neugesplitter.domain.history

import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import java.time.Instant

/** A group setting a `group.updated` can change. */
enum class Setting {
    SCALE,
    NAME,
    DESCRIPTION,
    EMOJI,
    COLOUR,
    DEFAULT_PARTICIPANTS,
}

/** What one event did to a group, as its history tells it (HI-2). */
sealed interface Happened {
    data object Created : Happened

    /** Some of the group's settings changed, in [settings]. */
    data class Changed(val settings: Set<Setting>) : Happened

    data class MemberAdded(val member: MemberId) : Happened

    /** Member [member] was called [from] until now. */
    data class MemberRenamed(val member: MemberId, val from: String, val to: String) : Happened

    data class MemberRemoved(val member: MemberId) : Happened

    /** Entry [entry] was [change]d; [description] is what it said just after, or at deletion. */
    data class Entry(val entry: EntryId, val change: Revision, val description: String) : Happened

    /** A recurring series or one of its occurrences changed (RE-1). */
    data object Recurring : Happened

    /** Entry [entry], which said [description], made way for a series repeating it (RE-1). */
    data class MadeRecurring(val entry: EntryId, val description: String) : Happened
}

/** One step in the history of a group: what [happened], by which member, and when (HI-2). */
data class Happening(val happened: Happened, val by: MemberId, val at: Instant)

/** [happening], told by the events at [events] in the log (HI-2). */
data class Step(val happening: Happening, val events: IntRange)

/**
 * [history] with what one action wrote at once told as one step (HI-2): neighbouring steps by one
 * member at one moment merge where together they tell one thing — settings changed, an entry made
 * recurring, a series changed.
 */
fun steps(history: List<Happening>): List<Step> {
    val steps = mutableListOf<Step>()
    history.forEachIndexed { index, happening ->
        val last =
            steps.lastOrNull()?.takeIf {
                it.happening.by == happening.by && it.happening.at == happening.at
            }
        val merged = last?.let { merge(it.happening.happened, happening.happened) }
        if (last != null && merged != null) {
            steps[steps.lastIndex] =
                Step(last.happening.copy(happened = merged), last.events.first..index)
        } else {
            steps += Step(happening, index..index)
        }
    }
    return steps
}

/** What [first] and then [second] tell as one step, if they tell one thing. */
private fun merge(first: Happened, second: Happened): Happened? =
    when {
        first is Happened.Changed && second is Happened.Changed ->
            Happened.Changed(first.settings + second.settings)
        first is Happened.Entry &&
            first.change == Revision.DELETED &&
            second is Happened.Recurring -> Happened.MadeRecurring(first.entry, first.description)
        first is Happened.MadeRecurring && second is Happened.Recurring -> first
        first is Happened.Recurring && second is Happened.Recurring -> first
        else -> null
    }

/**
 * The history of the group [events] make, one step per event, oldest first (HI-2). The log is
 * folded through [Group], so that names and descriptions are those of the moment.
 */
fun history(events: List<Event>): List<Happening> {
    val first = events.firstOrNull() ?: return emptyList()
    var group = Group.replay(listOf(first))
    val history = mutableListOf(Happening(Happened.Created, first.by, first.at))
    for (event in events.drop(1)) {
        val before = group
        group += event
        val happened =
            when (event) {
                is Event.GroupCreated -> Happened.Created
                is Event.GroupUpdated -> Happened.Changed(settings(event.group))
                is Event.MemberAdded -> Happened.MemberAdded(event.member.id)
                is Event.MemberRenamed ->
                    Happened.MemberRenamed(
                        event.target,
                        before.members.getValue(event.target).name,
                        event.name,
                    )
                is Event.MemberRemoved -> Happened.MemberRemoved(event.target)
                is Event.EntryCreated ->
                    Happened.Entry(event.entry.id, Revision.CREATED, event.entry.description)
                is Event.EntryUpdated ->
                    Happened.Entry(
                        event.target,
                        if (event.target in before.deleted) Revision.RESTORED else Revision.EDITED,
                        group.entries.getValue(event.target).description,
                    )
                is Event.EntryDeleted ->
                    Happened.Entry(
                        event.target,
                        Revision.DELETED,
                        group.deleted.getValue(event.target).description,
                    )
                is Event.SeriesCreated,
                is Event.SeriesAmended,
                is Event.SeriesStopped,
                is Event.SeriesContinued,
                is Event.SeriesDeleted,
                is Event.OccurrenceConfirmed,
                is Event.OccurrenceDeleted,
                is Event.OccurrenceAmended -> Happened.Recurring
            }
        history += Happening(happened, event.by, event.at)
    }
    return history
}

private fun settings(changes: GroupChanges): Set<Setting> = buildSet {
    if (changes.scale is Change.To) add(Setting.SCALE)
    if (changes.name is Change.To) add(Setting.NAME)
    if (changes.description is Change.To) add(Setting.DESCRIPTION)
    if (changes.emoji is Change.To) add(Setting.EMOJI)
    if (changes.colour is Change.To) add(Setting.COLOUR)
    if (changes.defaultParticipants is Change.To) add(Setting.DEFAULT_PARTICIPANTS)
}
