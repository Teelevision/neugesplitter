package eu.teele.neugesplitter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.history.Version
import eu.teele.neugesplitter.domain.history.versions
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * @property title the entry's description as of its last version, blank while there is none.
 * @property names every member of the group by name, removed ones too.
 * @property versions newest first; empty once loaded only if the entry or its group is gone.
 */
data class EntryHistoryState(
    val loaded: Boolean = false,
    val title: String = "",
    val scale: Scale = Scale.DEFAULT,
    val names: Map<MemberId, String> = emptyMap(),
    val versions: List<Version> = emptyList(),
)

/** The previous versions of one entry, and its deletion if there is one (HI-3). */
class EntryHistoryViewModel(repository: GroupRepository, group: GroupId, entry: EntryId) :
    ViewModel() {

    val state: StateFlow<EntryHistoryState> =
        repository
            .log(group)
            .map { events ->
                val versions = versions(events, entry).asReversed()
                if (events.isEmpty()) {
                    EntryHistoryState(loaded = true)
                } else {
                    val replayed = Group.replay(events)
                    EntryHistoryState(
                        loaded = true,
                        title = versions.firstOrNull()?.after?.description.orEmpty(),
                        scale = replayed.scale,
                        names = replayed.members.mapValues { it.value.name },
                        versions = versions,
                    )
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryHistoryState())
}
