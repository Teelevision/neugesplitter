package eu.teele.neugesplitter.domain.history

import eu.teele.neugesplitter.domain.entry.Entry as Content
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.recurrence.Decision
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Series
import eu.teele.neugesplitter.domain.recurrence.Stop
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.domain.value.TodoId
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

/** A group's settings at one moment, which a `group.updated` changes. */
data class Settings(
    val scale: Scale,
    val name: String,
    val description: String?,
    val emoji: String?,
    val colour: Colour?,
    val defaultParticipants: List<MemberId>?,
) {
    companion object {
        fun of(group: Group): Settings =
            with(group) { Settings(scale, name, description, emoji, colour, defaultParticipants) }
    }
}

/** What one event did to a recurring series or one of its occurrences (RE-1, RE-7). */
sealed interface SeriesChange {
    /** It began on [from], doing [segment]. */
    data class Started(val from: EntryDate, val segment: Segment) : SeriesChange

    /** From [from] on it does [after], where it did [before]; null before its first day. */
    data class Amended(val from: EntryDate, val before: Segment?, val after: Segment) :
        SeriesChange

    data class Stopped(val stop: Stop) : SeriesChange

    /** It runs again on the days of [span]. */
    data class Continued(val span: Stop) : SeriesChange

    data object Deleted : SeriesChange

    data class Confirmed(val due: EntryDate) : SeriesChange

    /** The occurrence due on [due] was declined or deleted (RE-5, RE-15). */
    data class LeftOut(val due: EntryDate) : SeriesChange

    /** The occurrence due on [due] became [after]; it was [before], null if nothing was due. */
    data class Edited(val due: EntryDate, val before: Content?, val after: Content) : SeriesChange
}

/** What one event did to a todo (TD-1). */
sealed interface TodoChange {
    data object Created : TodoChange

    /** It was [before] and became [after]; the same while it is deleted. */
    data class Edited(val before: Todo, val after: Todo) : TodoChange

    data object Deleted : TodoChange
}

/** What one event did to several todos at once (TD-3, TD-5). */
enum class TodosChange {
    MOVED,
    COMPLETED,
    REOPENED,
}

/** What one event did to a group, as its history tells it (HI-2). */
sealed interface Happened {
    data object Created : Happened

    /** Some of the group's settings changed, in [settings], from [before] to [after]. */
    data class Changed(val settings: Set<Setting>, val before: Settings, val after: Settings) :
        Happened

    data class MemberAdded(val member: MemberId) : Happened

    /** Member [member] was called [from] until now. */
    data class MemberRenamed(val member: MemberId, val from: String, val to: String) : Happened

    data class MemberRemoved(val member: MemberId) : Happened

    /**
     * Entry [entry] was [change]d from [before], null when created, to [after]; for a deletion both
     * are what it was at deletion.
     */
    data class Entry(
        val entry: EntryId,
        val change: Revision,
        val before: Content?,
        val after: Content,
    ) : Happened {
        /** What it said just after, or at deletion. */
        val description: String
            get() = after.description
    }

    /** Series [series], which said [description] then, underwent [change] (RE-1). */
    data class Recurring(val series: SeriesId, val description: String, val change: SeriesChange) :
        Happened

    /**
     * Entry [entry], which said [description], made way for series [series] repeating it as
     * [segment] (RE-1).
     */
    data class MadeRecurring(
        val entry: EntryId,
        val description: String,
        val series: SeriesId,
        val segment: Segment,
    ) : Happened

    /** Todo [todo], which said [description] then, underwent [change] (TD-1). */
    data class Todo(val todo: TodoId, val description: String, val change: TodoChange) : Happened

    /** The todos that said [descriptions] then underwent [change], once each in that order. */
    data class Todos(val change: TodosChange, val descriptions: List<String>) : Happened
}

/** The series [this] concerns, if any. */
val Happened.series: SeriesId?
    get() =
        when (this) {
            is Happened.Recurring -> series
            is Happened.MadeRecurring -> series
            else -> null
        }

/** One step in the history of a group: what [happened], by which member, and when (HI-2). */
data class Happening(val happened: Happened, val by: MemberId, val at: Instant)

/** [happening], told by the events at [events] in the log (HI-2). */
data class Step(val happening: Happening, val events: IntRange)

/**
 * [history] with what one action wrote at once told as one step (HI-2): neighbouring steps by one
 * member at one moment merge where together they tell one thing — settings changed, an entry made
 * recurring, a series changed. A series change is told by its first event, the one that names the
 * most.
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
            Happened.Changed(first.settings + second.settings, first.before, second.after)
        first is Happened.Entry &&
            first.change == Revision.DELETED &&
            second is Happened.Recurring &&
            second.change is SeriesChange.Started ->
            Happened.MadeRecurring(
                first.entry,
                first.description,
                second.series,
                second.change.segment,
            )
        first is Happened.MadeRecurring &&
            second is Happened.Recurring &&
            first.series == second.series -> first
        first is Happened.Recurring &&
            second is Happened.Recurring &&
            first.series == second.series -> first
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
                is Event.GroupUpdated ->
                    Happened.Changed(settings(event.group), Settings.of(before), Settings.of(group))
                is Event.MemberAdded -> Happened.MemberAdded(event.member.id)
                is Event.MemberRenamed ->
                    Happened.MemberRenamed(
                        event.target,
                        before.members.getValue(event.target).name,
                        event.name,
                    )
                is Event.MemberRemoved -> Happened.MemberRemoved(event.target)
                is Event.EntryCreated ->
                    Happened.Entry(event.entry.id, Revision.CREATED, null, event.entry)
                is Event.EntryUpdated ->
                    Happened.Entry(
                        event.target,
                        if (event.target in before.deleted) Revision.RESTORED else Revision.EDITED,
                        before.entries[event.target] ?: before.deleted.getValue(event.target),
                        group.entries.getValue(event.target),
                    )
                is Event.EntryDeleted -> {
                    val deleted = group.deleted.getValue(event.target)
                    Happened.Entry(event.target, Revision.DELETED, deleted, deleted)
                }
                is Event.SeriesCreated ->
                    Happened.Recurring(
                        event.series,
                        event.segment.template.description,
                        SeriesChange.Started(event.from, event.segment),
                    )
                is Event.SeriesAmended ->
                    Happened.Recurring(
                        event.target,
                        event.segment.template.description,
                        SeriesChange.Amended(
                            event.from,
                            before.series[event.target]?.segment(event.from),
                            event.segment,
                        ),
                    )
                is Event.SeriesStopped ->
                    before.recurring(
                        event.target,
                        event.from,
                        SeriesChange.Stopped(Stop(event.from, event.until)),
                    )
                is Event.SeriesContinued ->
                    before.recurring(
                        event.target,
                        event.from,
                        SeriesChange.Continued(Stop(event.from, event.until)),
                    )
                is Event.SeriesDeleted -> before.recurring(event.target, null, SeriesChange.Deleted)
                is Event.OccurrenceConfirmed ->
                    before.recurring(event.series, event.due, SeriesChange.Confirmed(event.due))
                is Event.OccurrenceDeleted -> {
                    val was = before.series[event.series]?.produced(event.due)
                    Happened.Recurring(
                        event.series,
                        was?.description ?: before.described(event.series, event.due),
                        SeriesChange.LeftOut(event.due),
                    )
                }
                is Event.OccurrenceAmended -> {
                    val after =
                        event.entry.on(EntryId.occurrence(event.series, event.due), event.date)
                    Happened.Recurring(
                        event.series,
                        after.description,
                        SeriesChange.Edited(
                            event.due,
                            before.series[event.series]?.produced(event.due),
                            after,
                        ),
                    )
                }
                is Event.TodoCreated ->
                    Happened.Todo(event.todo.id, event.todo.description, TodoChange.Created)
                is Event.TodoUpdated -> {
                    val after = group.todo(event.target)
                    Happened.Todo(
                        event.target,
                        after.description,
                        TodoChange.Edited(before.todo(event.target), after),
                    )
                }
                is Event.TodoMoved -> before.todos(TodosChange.MOVED, event.moves.map { it.target })
                is Event.TodoCompleted -> before.todos(TodosChange.COMPLETED, event.targets)
                is Event.TodoReopened -> before.todos(TodosChange.REOPENED, event.targets)
                is Event.TodoDeleted -> before.happened(event.target, TodoChange.Deleted)
            }
        history += Happening(happened, event.by, event.at)
    }
    return history
}

/** [change] to [series], described as it was on [on], or as it last was without one. */
private fun Group.recurring(series: SeriesId, on: EntryDate?, change: SeriesChange) =
    Happened.Recurring(series, described(series, on), change)

/** Todo [target] as it is listed, or as it was deleted. */
private fun Group.todo(target: TodoId): Todo =
    todos.firstOrNull { it.id == target } ?: binned.getValue(target)

private fun Group.happened(target: TodoId, change: TodoChange) =
    Happened.Todo(target, todo(target).description, change)

private fun Group.todos(change: TodosChange, targets: List<TodoId>) =
    Happened.Todos(change, targets.distinct().map { todo(it).description })

private fun Group.described(series: SeriesId, on: EntryDate?): String {
    val found = this.series.getValue(series)
    val segment = on?.let(found::segment) ?: found.segments.last().second
    return segment.template.description
}

/** What [due] holds as decided or scheduled, stops aside; null if nothing. */
private fun Series.produced(due: EntryDate): Content? {
    val id = EntryId.occurrence(this.id, due)
    return when (val decision = decisions[due]) {
        is Decision.Amended -> decision.entry.on(id, decision.date)
        Decision.Deleted -> null
        Decision.Confirmed,
        null -> segment(due)?.template?.on(id, due)
    }
}

private fun settings(changes: GroupChanges): Set<Setting> = buildSet {
    if (changes.scale is Change.To) add(Setting.SCALE)
    if (changes.name is Change.To) add(Setting.NAME)
    if (changes.description is Change.To) add(Setting.DESCRIPTION)
    if (changes.emoji is Change.To) add(Setting.EMOJI)
    if (changes.colour is Change.To) add(Setting.COLOUR)
    if (changes.defaultParticipants is Change.To) add(Setting.DEFAULT_PARTICIPANTS)
}
