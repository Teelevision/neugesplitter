package eu.teele.neugesplitter.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.SyncState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** @property sync null once loaded only if the group is no longer shared, or no longer here. */
data class SyncProblemState(val loaded: Boolean = false, val sync: SyncState? = null)

/** Why a group stopped syncing and what can be done about it (SY-15). */
class SyncProblemViewModel(
    repository: GroupRepository,
    private val group: GroupId,
    /** Runs a pass over a group at once (SY-9, TR-13). */
    private val syncNow: (GroupId) -> Unit,
) : ViewModel() {

    val state: StateFlow<SyncProblemState> =
        repository.backends
            .state(group)
            .map { SyncProblemState(loaded = true, sync = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncProblemState())

    /** A pass finds whether the storage agrees with this device again. */
    fun check() = syncNow(group)
}
