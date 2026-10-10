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
 */
data class GroupListState(
    val groups: List<ListedGroup>? = null,
    val selected: Set<GroupId> = emptySet(),
)

class GroupListViewModel(private val repository: GroupRepository, today: () -> EntryDate) :
    ViewModel() {

    private val selected = MutableStateFlow(emptySet<GroupId>())

    val state: StateFlow<GroupListState> =
        combine(repository.list(today), selected) { groups, selected ->
                GroupListState(
                    groups,
                    selected.filterTo(mutableSetOf()) { id -> groups.any { it.id == id } },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupListState())

    fun toggle(id: GroupId) {
        selected.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    /** Deletes the selected groups from this device (GR-11). The screen has asked first. */
    fun deleteSelected() {
        val doomed = state.value.selected
        selected.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) { doomed.forEach(repository::delete) }
    }
}
