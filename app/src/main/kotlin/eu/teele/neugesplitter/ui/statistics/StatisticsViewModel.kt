package eu.teele.neugesplitter.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.statistics.Statistics
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * @property loaded false until the group has been read once.
 * @property statistics null once loaded only if this device no longer holds the group.
 * @property me the member this installation acts as.
 */
data class StatisticsState(
    val loaded: Boolean = false,
    val statistics: Statistics? = null,
    val me: MemberId? = null,
)

/** The charts' view of a group, read afresh whenever it changes (BA-8). */
class StatisticsViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val today: () -> EntryDate,
) : ViewModel() {

    val state: StateFlow<StatisticsState> =
        repository
            .group(group)
            .map { log ->
                StatisticsState(
                    loaded = true,
                    statistics = log?.let { Statistics(it, today()) },
                    me = repository.actingAs(group),
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsState())
}
