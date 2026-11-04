package eu.teele.neugesplitter.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.ListedEntry
import eu.teele.neugesplitter.storage.Overview
import eu.teele.neugesplitter.storage.settings.SyncState
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @property loaded false until the group has been read once.
 * @property overview null once loaded only if this device no longer holds the group.
 * @property deleting the entry deleted but not yet written as deleted, which the overview leaves
 *   out while its undo is offered (HI-1).
 * @property sync how the group's sync stands (SY-6, SY-15); null for a group kept only on this
 *   device.
 * @property suggestions how many suggested occurrences wait for someone (RE-10).
 * @property now when this state was read, which relative times count back from.
 * @property filter what the entries are narrowed to (EN-12).
 * @property listed the entries that match [filter], which the overview lists.
 */
data class OverviewState(
    val loaded: Boolean = false,
    val overview: Overview? = null,
    val deleting: EntryId? = null,
    val sync: SyncState? = null,
    val suggestions: Int = 0,
    val now: Instant = Instant.EPOCH,
    val filter: EntryFilter = EntryFilter(),
    val listed: List<ListedEntry> = emptyList(),
)

/**
 * @param scope outlives this view model, so that a deletion still offered for undo is written when
 *   the screen goes.
 */
class OverviewViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val identifier: () -> String,
    val today: () -> EntryDate,
    private val now: () -> Instant,
    private val scope: CoroutineScope,
) : ViewModel() {

    private val deleting = MutableStateFlow<EntryId?>(null)
    private val committing = MutableStateFlow<Set<EntryId>>(emptySet())
    private val filter = MutableStateFlow(EntryFilter())
    private val overviewAndCommitting =
        combine(repository.overview(group, today), committing) { overview, committing ->
            overview to committing
        }

    /** The settled balance (BA-7) as it would be without the entry offered for undo. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ownWithout: Flow<Pair<Set<EntryId>, Amount?>?> =
        combine(deleting, committing) { deleting, committing ->
                committing + listOfNotNull(deleting)
            }
            .distinctUntilChanged()
            .flatMapLatest { entries ->
                if (entries.isEmpty()) flowOf(null)
                else
                    repository
                        .group(group)
                        .map { log ->
                            val me = repository.actingAs(group)
                            entries to
                                me?.let { member ->
                                    log?.let { group ->
                                        entries
                                            .fold(group) { state, entry ->
                                                state.without(entry, today())
                                            }
                                            .settlement(today())[member]
                                    }
                                }
                        }
                        .flowOn(Dispatchers.IO)
            }

    val state: StateFlow<OverviewState> =
        combine(
                overviewAndCommitting,
                deleting,
                ownWithout,
                repository.backends.state(group),
                repository.pending(group, today),
            ) { overviewAndCommitting, deleting, without, sync, suggestions ->
                val (overview, committing) = overviewAndCommitting
                val persisted =
                    committing.filterTo(mutableSetOf()) { id ->
                        overview == null || overview.entries.none { it.id == id }
                    }
                if (persisted.isNotEmpty())
                    this@OverviewViewModel.committing.update { it - persisted }
                val hidden = committing + listOfNotNull(deleting)
                OverviewState(
                    loaded = true,
                    overview =
                        overview?.copy(
                            own = if (without?.first == hidden) without.second else overview.own,
                            entries = overview.entries.filterNot { it.id in hidden },
                        ),
                    deleting = deleting,
                    sync = sync,
                    suggestions = suggestions,
                    now = now(),
                )
            }
            .combine(filter) { state, filter ->
                val overview = state.overview
                state.copy(
                    filter = filter,
                    listed = overview?.let { filter.matching(it.entries, it.scale) }.orEmpty(),
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewState())

    /** Narrows the entries listed to those [filter] matches (EN-12). */
    fun filter(filter: EntryFilter) {
        this.filter.value = filter
    }

    /** Hides [entry] and offers to undo; the one offered before is deleted for good (HI-1). */
    fun delete(entry: EntryId) {
        deleting.value?.takeIf { it != entry }?.let { committing.update { ids -> ids + it } }
        deleting.getAndUpdate { entry }?.takeIf { it != entry }?.let(::write)
    }

    fun undo() {
        deleting.value = null
    }

    /** The undo is no longer offered, so the deletion is written. */
    fun undoGone() {
        val entry = deleting.value ?: return
        committing.update { it + entry }
        if (deleting.compareAndSet(entry, null)) write(entry) else committing.update { it - entry }
    }

    override fun onCleared() {
        undoGone()
    }

    /** Deletes [entry]; an occurrence goes alone and its series runs on (RE-15). */
    private fun write(entry: EntryId) {
        scope.launch {
            val by =
                repository.actingAs(group)
                    ?: run {
                        committing.update { it - entry }
                        return@launch
                    }
            val occurrence = repository.load(group)?.occurrence(entry, today())
            val deleted =
                if (occurrence == null) {
                    Event.EntryDeleted(EventId.parse(identifier()), by, now(), entry)
                } else {
                    val (series, due) = occurrence.series to occurrence.due
                    Event.OccurrenceDeleted(EventId.occurrence(series, due), by, now(), series, due)
                }
            repository.append(group, listOf(deleted), today())
        }
    }
}
