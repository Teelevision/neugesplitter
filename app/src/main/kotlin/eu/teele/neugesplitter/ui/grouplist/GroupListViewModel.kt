package eu.teele.neugesplitter.ui.grouplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.ListedGroup
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @property groups null until the list has been read once, so that an empty list means there are no
 *   groups rather than that none have been read yet.
 * @property selected the groups a long press has selected, only ever ones still in [groups].
 * @property exporting the selection is of the groups to export, not to delete (DA-3).
 * @property duplicating a group is being copied.
 */
data class GroupListState(
    val groups: List<ListedGroup>? = null,
    val selected: Set<GroupId> = emptySet(),
    val exporting: Boolean = false,
    val duplicating: Boolean = false,
) {
    val selecting: Boolean
        get() = exporting || selected.isNotEmpty()
}

class GroupListViewModel(
    private val repository: GroupRepository,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
) : ViewModel() {

    private val selected = MutableStateFlow(emptySet<GroupId>())
    private val exporting = MutableStateFlow(false)
    private val duplicating = MutableStateFlow(false)

    val state: StateFlow<GroupListState> =
        combine(repository.list(today), selected, exporting, duplicating) {
                groups,
                selected,
                exporting,
                duplicating ->
                GroupListState(
                    groups,
                    selected.filterTo(mutableSetOf()) { id -> groups.any { it.id == id } },
                    exporting,
                    duplicating,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupListState())

    fun toggle(id: GroupId) {
        selected.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selected.value = emptySet()
        exporting.value = false
    }

    /** Selects every group, to export those still selected once the user is done (DA-3). */
    fun startExport() {
        selected.value = state.value.groups.orEmpty().mapTo(mutableSetOf()) { it.id }
        exporting.value = true
    }

    /** The groups to export, in the order of the list; the selection is done with. */
    fun export(): List<GroupId> {
        val chosen =
            state.value.groups.orEmpty().map { it.id }.filter { it in state.value.selected }
        clearSelection()
        return chosen
    }

    /** Deletes the selected groups from this device (GR-11). The screen has asked first. */
    fun deleteSelected() {
        val doomed = state.value.selected
        selected.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) { doomed.forEach(repository::delete) }
    }

    /**
     * The name to suggest for a copy of the one selected group: the first of [copy]'s names,
     * unnumbered and then numbered from 2, that no group has yet; null unless one is selected.
     */
    fun copyName(copy: (name: String, number: Int?) -> String): String? {
        val groups = state.value.groups.orEmpty()
        val source = groups.singleOrNull { it.id in state.value.selected } ?: return null
        val taken = groups.mapTo(mutableSetOf()) { it.name }
        return generateSequence(1) { it + 1 }
            .map { copy(source.name, it.takeIf { number -> number > 1 }) }
            .first { it !in taken }
    }

    /**
     * Copies the one selected group's whole history into a new group named [name], kept only on
     * this device, as the same member; [GroupListState.duplicating] while that lasts.
     */
    fun duplicateSelected(name: String) {
        val source = state.value.selected.singleOrNull() ?: return
        val trimmed =
            name.trim().ifEmpty {
                return
            }
        selected.value = emptySet()
        duplicating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val me = repository.actingAs(source) ?: return@launch
                val renamed =
                    Event.GroupUpdated(
                        EventId.parse(identifier()),
                        me,
                        now(),
                        GroupChanges(name = Change.To(trimmed)),
                    )
                val copy = GroupId.parse(identifier())
                repository.importGroup(copy, repository.texts(source), me, listOf(renamed), today())
                state.first { listed -> listed.groups.orEmpty().any { it.id == copy } }
            } finally {
                duplicating.value = false
            }
        }
    }
}
