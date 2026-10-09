package eu.teele.neugesplitter.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.history.Happening
import eu.teele.neugesplitter.domain.history.history
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.GroupRepository
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
 * @property history newest first.
 * @property unseen the places in [history] of the changes not seen yet (HI-5).
 * @property deleted the entries deleted now, which open in their history rather than their detail.
 * @property sync how the group's sync stands; null for a group kept only on this device.
 * @property now when this state was read, which relative times count back from.
 */
data class ActivityState(
    val loaded: Boolean = false,
    val title: String? = null,
    val names: Map<MemberId, String> = emptyMap(),
    val history: List<Happening> = emptyList(),
    val unseen: Set<Int> = emptySet(),
    val deleted: Set<EntryId> = emptySet(),
    val sync: SyncState? = null,
    val now: Instant = Instant.EPOCH,
)

/**
 * What changed in a group and by whom (HI-2), which of it has not been seen yet (HI-5, HI-7), and
 * how its sync stands (SY-6, ui-design 4).
 */
class ActivityViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val now: () -> Instant,
    /** Runs a pass over a group at once (SY-9, TR-13). */
    private val syncNow: (GroupId) -> Unit,
) : ViewModel() {

    val state: StateFlow<ActivityState> =
        combine(
                repository.log(group),
                repository.unseen(group),
                repository.backends.state(group),
            ) { events, unseen, sync ->
                if (events.isEmpty()) return@combine ActivityState(loaded = true)
                val replayed = Group.replay(events)
                ActivityState(
                    loaded = true,
                    title = listOfNotNull(replayed.emoji, replayed.name).joinToString(" "),
                    names = replayed.members.mapValues { it.value.name },
                    history = history(events).asReversed(),
                    unseen = unseen.mapTo(mutableSetOf()) { events.lastIndex - it },
                    deleted = replayed.deleted.keys,
                    sync = sync,
                    now = now(),
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityState())

    fun sync() = syncNow(group)

    /** Marks the change at [index] in the history as seen. */
    fun read(index: Int) {
        val position = state.value.history.lastIndex - index
        viewModelScope.launch(Dispatchers.IO) { repository.seen(group, position) }
    }

    fun readAll() {
        viewModelScope.launch(Dispatchers.IO) { repository.seenAll(group) }
    }
}
