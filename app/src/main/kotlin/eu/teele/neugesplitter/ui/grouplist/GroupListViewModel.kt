package eu.teele.neugesplitter.ui.grouplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.ListedGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @property groups null until the list has been read once, so that an empty list means there are no
 *   groups rather than that none have been read yet.
 * @property selected the groups a long press has selected, only ever ones still in [groups].
 * @property exporting the selection is of the groups to export, not to delete (DA-3).
 */
data class GroupListState(
    val groups: List<ListedGroup>? = null,
    val selected: Set<GroupId> = emptySet(),
    val exporting: Boolean = false,
) {
    val selecting: Boolean
        get() = exporting || selected.isNotEmpty()
}

class GroupListViewModel(private val repository: GroupRepository, today: () -> EntryDate) :
    ViewModel() {

    private val selected = MutableStateFlow(emptySet<GroupId>())
    private val exporting = MutableStateFlow(false)

    val state: StateFlow<GroupListState> =
        combine(repository.list(today), selected, exporting) { groups, selected, exporting ->
                GroupListState(
                    groups,
                    selected.filterTo(mutableSetOf()) { id -> groups.any { it.id == id } },
                    exporting,
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
}
