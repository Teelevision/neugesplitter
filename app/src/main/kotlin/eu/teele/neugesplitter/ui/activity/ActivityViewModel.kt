package eu.teele.neugesplitter.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.category.Category
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.history.Step
import eu.teele.neugesplitter.domain.history.history
import eu.teele.neugesplitter.domain.history.steps
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.ListedEntry
import eu.teele.neugesplitter.storage.Replayed
import eu.teele.neugesplitter.storage.settings.SyncState
import eu.teele.neugesplitter.ui.history.day
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @property title the group's emoji and name; null once loaded only if this device no longer holds
 *   the group.
 * @property names every member of the group by name, removed ones too.
 * @property history newest first, what one action wrote at once as one step (HI-2).
 * @property suggestions the suggested occurrences waiting to be confirmed or declined, oldest first
 *   (RE-4, RE-10), as the overview would list them once confirmed.
 * @property scale the group's decimal places, which amounts are shown with.
 * @property sync how the group's sync stands; null for a group kept only on this device.
 * @property now when this state was read, which relative times count back from.
 */
data class ActivityState(
    val loaded: Boolean = false,
    val title: String? = null,
    val names: Map<MemberId, String> = emptyMap(),
    val categories: Map<CategoryId, Category> = emptyMap(),
    val history: List<HistoryItem> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val scale: Scale = Scale.of(2),
    val sync: SyncState? = null,
    val now: Instant = Instant.EPOCH,
) {
    /** Whether any step holds changes not seen yet (HI-5). */
    val unseen: Boolean = history.any { it.unseen }
}

/**
 * A [step] of the history, filed under the [day] it fell on here, with whether it holds changes not
 * seen yet (HI-5) or not on the group's backend yet (SY-16).
 */
data class HistoryItem(
    val step: Step,
    val day: EntryDate,
    val unseen: Boolean = false,
    val unsynced: Boolean = false,
)

/** A replayed log as the history shows it, worked out once for each version of the log. */
private class Told(val replayed: Replayed, val steps: List<HistoryItem>)

/** The occurrence of [series] due on [due], offered as [entry] (RE-4). */
data class Suggestion(val series: SeriesId, val due: EntryDate, val entry: ListedEntry)

/** [group]'s todos not done that are due by [today], earliest first (TD-7). */
fun dueTodos(group: Group, today: EntryDate): List<Todo> =
    group.todos
        .filter { todo -> !todo.done && todo.due.let { it != null && it <= today } }
        .sortedBy { it.due }

/**
 * What changed in a group and by whom (HI-2), which of it has not been seen yet (HI-5, HI-7), the
 * suggestions waiting for someone (RE-4, RE-10), and how its sync stands (SY-6, ui-design 4).
 */
class ActivityViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    /** Runs a pass over a group at once (SY-9, TR-13). */
    private val syncNow: (GroupId) -> Unit,
    private val syncAndWait: suspend (GroupId) -> Unit,
    /** Stops or resumes syncing the group (SY-16). */
    private val pauseSync: (GroupId, Boolean) -> Unit = { _, _ -> },
    /** Drops what the backend does not hold yet, while paused (SY-17). */
    private val discardLocal: suspend (GroupId) -> Unit = {},
) : ViewModel() {

    private val told =
        repository
            .replays(group)
            .map { replayed ->
                replayed?.let {
                    Told(
                        it,
                        steps(history(it.events)).asReversed().map { step ->
                            HistoryItem(step, day(step.happening.at))
                        },
                    )
                }
            }
            .flowOn(Dispatchers.IO)

    val state: StateFlow<ActivityState> =
        combine(
                told,
                repository.unseen(group),
                repository.unsynced(group),
                repository.backends.state(group),
                // Changes when a new day brings suggestions, as the log does not.
                repository.pending(group, today),
            ) { told, unseen, unsynced, sync, _ ->
                if (told == null || told.replayed.events.isEmpty())
                    return@combine ActivityState(loaded = true)
                val events = told.replayed.events
                val replayed = told.replayed.group
                val me = repository.actingAs(group)
                fun name(member: MemberId) = replayed.members[member]?.name ?: member.hex.take(8)
                val fresh = unseen.mapNotNullTo(mutableSetOf()) { events.getOrNull(it)?.id }
                ActivityState(
                    loaded = true,
                    title = listOfNotNull(replayed.emoji, replayed.name).joinToString(" "),
                    names = replayed.members.mapValues { it.value.name },
                    categories = replayed.categories,
                    history =
                        if (fresh.isEmpty() && unsynced.isEmpty()) told.steps
                        else
                            told.steps.map { item ->
                                val parts = item.step.parts
                                val isUnseen = parts.any { it.event in fresh }
                                val isUnsynced = parts.any { it.event in unsynced }
                                if (isUnseen || isUnsynced)
                                    item.copy(unseen = isUnseen, unsynced = isUnsynced)
                                else item
                            },
                    suggestions =
                        replayed
                            .occurrences(today())
                            .filter { it.pending }
                            .map { occurrence ->
                                val entry = occurrence.entry
                                val listed =
                                    ListedEntry(
                                        id = entry.id,
                                        kind = entry.kind,
                                        description = entry.description,
                                        date = entry.date,
                                        amount = entry.amount,
                                        payers = entry.payers.members.map(::name),
                                        own = me?.let { entry.contributions()[it] },
                                        repeats = true,
                                        edited = false,
                                        unseen = false,
                                    )
                                Suggestion(occurrence.series, occurrence.due, listed)
                            },
                    scale = replayed.scale,
                    sync = sync,
                    now = now(),
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityState())

    fun sync() = syncNow(group)

    suspend fun refresh() = syncAndWait(group)

    /** Stops syncing the group until [resume] (SY-16). */
    fun pause() = pauseSync(group, true)

    fun resume() = pauseSync(group, false)

    /** Drops every change the backend does not hold yet; only while paused (SY-17). */
    fun discard() {
        if (state.value.sync?.paused != true) return
        viewModelScope.launch(Dispatchers.IO) { discardLocal(group) }
    }

    /** Counts [suggestion] from now on (RE-4). */
    fun confirm(suggestion: Suggestion) =
        decide(suggestion) { id, by, at ->
            Event.OccurrenceConfirmed(id, by, at, suggestion.series, suggestion.due)
        }

    /** Leaves [suggestion] out; its series carries on (RE-5). */
    fun decline(suggestion: Suggestion) =
        decide(suggestion) { id, by, at ->
            Event.OccurrenceDeleted(id, by, at, suggestion.series, suggestion.due)
        }

    /** Writes [event] under the id every installation gives [suggestion] (format-spec 6). */
    private fun decide(suggestion: Suggestion, event: (EventId, MemberId, Instant) -> Event) {
        viewModelScope.launch(Dispatchers.IO) {
            val by = repository.actingAs(group) ?: return@launch
            val id = EventId.occurrence(suggestion.series, suggestion.due)
            try {
                repository.append(group, listOf(event(id, by, now())), today())
            } catch (_: Malformed) {
                // Decided already, by a second tap or by someone else whose decision arrived first.
            }
        }
    }

    /** Marks [step] as seen, every change it holds. */
    fun read(step: Step) {
        val told = step.parts.mapTo(mutableSetOf()) { it.event }
        viewModelScope.launch(Dispatchers.IO) {
            repository.events(group).forEachIndexed { position, event ->
                if (event.id in told) repository.seen(group, position)
            }
        }
    }

    fun readAll() {
        viewModelScope.launch(Dispatchers.IO) { repository.seenAll(group) }
    }
}
