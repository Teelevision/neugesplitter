package eu.teele.neugesplitter.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.backend.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @property invitation null once loaded if the group is not shared or its secret key no longer
 *   opens on this device.
 * @property encrypted the group has a passphrase, which [invitation] lacks if it no longer opens.
 */
data class InviteState(
    val loaded: Boolean = false,
    val invitation: Invitation? = null,
    val encrypted: Boolean = false,
)

/** The invitation to shared group [group] (BE-18). */
class InviteViewModel(
    groups: GroupRepository,
    group: GroupId,
    secretKey: suspend (GroupId) -> String?,
    passphrase: suspend (GroupId) -> String?,
) : ViewModel() {

    private val mutable = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val (settings, encrypted) =
                withContext(Dispatchers.IO) {
                    groups.backends.settings(group) to (groups.backends.root(group) != null)
                }
            val invitation =
                settings?.let { at ->
                    secretKey(group)?.let {
                        Invitation(
                            Location(
                                at.endpoint,
                                at.bucket,
                                at.prefix,
                                at.region,
                                at.pathStyle,
                                at.accessKey,
                                it,
                            ),
                            if (encrypted) passphrase(group) else null,
                        )
                    }
                }
            mutable.value = InviteState(loaded = true, invitation, encrypted)
        }
    }
}
