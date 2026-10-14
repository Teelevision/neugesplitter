package eu.teele.neugesplitter.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.history.Step
import eu.teele.neugesplitter.domain.history.history
import eu.teele.neugesplitter.domain.history.steps
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.ListedEntry
import eu.teele.neugesplitter.storage.settings.SyncState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @property title the group's emoji and name; null once loaded only if this device no longer holds
 *   the group.
 * @property names every member of the group by name, removed ones too.
 * @property history newest first, what one action wrote at once as one step (HI-2).
 * @property unseen the places in [history] of the steps holding changes not seen yet (HI-5).
 * @property deleted the entries deleted now, which open in their history rather than their detail.
 * @property suggestions the suggested occurrences waiting to be confirmed or declined, oldest first
 *   (RE-4, RE-10), as the overview would list them once confirmed.
 * @property todos the todos not done that are due, earliest first (TD-7).
 * @property scale the group's decimal places, which amounts are shown with.
 * @property sync how the group's sync stands; null for a group kept only on this device.
 * @property now when this state was read, which relative times count back from.
 */
data class ActivityState(
    val loaded: Boolean = false,
    val title: String? = null,
    val names: Map<MemberId, String> = emptyMap(),
    val history: List<Step> = emptyList(),
    val unseen: Set<Int> = emptySet(),
    val deleted: Set<EntryId> = emptySet(),
    val suggestions: List<Suggestion> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val scale: Scale = Scale.of(2),
    val sync: SyncState? = null,
    val now: Instant = Instant.EPOCH,
)

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
) : ViewModel() {

    val state: StateFlow<ActivityState> =
        combine(
                repository.log(group),
                repository.unseen(group),
                repository.backends.state(group),
                // Changes when a new day brings suggestions, as the log does not.
                repository.pending(group, today),
            ) { events, unseen, sync, _ ->
                if (events.isEmpty()) return@combine ActivityState(loaded = true)
                val replayed = Group.replay(events)
                val me = repository.actingAs(group)
                fun name(member: MemberId) = replayed.members[member]?.name ?: member.hex.take(8)
                val history = steps(history(events)).asReversed()
                ActivityState(
                    loaded = true,
                    title = listOfNotNull(replayed.emoji, replayed.name).joinToString(" "),
                    names = replayed.members.mapValues { it.value.name },
                    history = history,
                    unseen =
                        history.indices.filterTo(mutableSetOf()) { index ->
                            history[index].events.any { it in unseen }
                        },
                    deleted = replayed.deleted.keys,
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
                    todos = dueTodos(replayed, today()),
                    scale = replayed.scale,
                    sync = sync,
                    now = now(),
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityState())

    fun sync() = syncNow(group)

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

    /** Marks the step at [index] in the history as seen, every change it holds. */
    fun read(index: Int) {
        val events = state.value.history[index].events
        viewModelScope.launch(Dispatchers.IO) { events.forEach { repository.seen(group, it) } }
    }

    fun readAll() {
        viewModelScope.launch(Dispatchers.IO) { repository.seenAll(group) }
    }
}
