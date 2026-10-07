package eu.teele.neugesplitter.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.Overview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * @property loaded false until the group has been read once.
 * @property overview null once loaded only if this device no longer holds the group.
 */
data class OverviewState(val loaded: Boolean = false, val overview: Overview? = null)

class OverviewViewModel(repository: GroupRepository, id: GroupId, today: () -> EntryDate) :
    ViewModel() {

    val state: StateFlow<OverviewState> =
        repository
            .overview(id, today())
            .map { OverviewState(loaded = true, overview = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewState())
}
