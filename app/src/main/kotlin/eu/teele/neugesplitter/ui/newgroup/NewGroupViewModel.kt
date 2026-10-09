package eu.teele.neugesplitter.ui.newgroup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.theme.GROUP_COLOURS
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Step {
    DETAILS,
    MEMBERS,
    STORAGE,
}

/**
 * What the new-group flow has been told so far (ui-design 3). Names are kept as typed and trimmed
 * only when the group is created.
 *
 * @property others the other members' names, in the order they were added; blank ones are skipped.
 */
data class NewGroupState(
    val id: GroupId,
    val step: Step = Step.DETAILS,
    val name: String = "",
    val description: String = "",
    val emoji: String? = null,
    val colour: Colour = GROUP_COLOURS.first(),
    val you: String = "",
    val others: List<String> = emptyList(),
    val shared: Boolean = false,
    val creating: Boolean = false,
    val failed: Boolean = false,
    val created: Boolean = false,
) {
    val canContinue: Boolean
        get() =
            when (step) {
                Step.DETAILS -> name.isNotBlank()
                Step.MEMBERS -> you.isNotBlank()
                Step.STORAGE -> !creating
            }
}

class NewGroupViewModel(
    private val repository: GroupRepository,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    /** Shared storage is chosen already, as when joining found an empty location. */
    shared: Boolean = false,
) : ViewModel() {

    private val mutable =
        MutableStateFlow(NewGroupState(GroupId.parse(identifier()), shared = shared))
    val state: StateFlow<NewGroupState> = mutable.asStateFlow()

    fun name(text: String) = mutable.update { it.copy(name = text) }

    fun description(text: String) = mutable.update { it.copy(description = text) }

    /** Chooses [emoji], or clears it if it was the one chosen: a group need not have one (GR-2). */
    fun emoji(emoji: String) =
        mutable.update { it.copy(emoji = emoji.takeIf { _ -> it.emoji != emoji }) }

    fun colour(colour: Colour) = mutable.update { it.copy(colour = colour) }

    fun you(text: String) = mutable.update { it.copy(you = text) }

    fun addMember() = mutable.update { it.copy(others = it.others + "") }

    fun other(index: Int, text: String) =
        mutable.update { it.copy(others = it.others.toMutableList().apply { set(index, text) }) }

    fun removeMember(index: Int) =
        mutable.update { it.copy(others = it.others.toMutableList().apply { removeAt(index) }) }

    /** Shared storage is set up once the group exists (BE-1, BE-2). */
    fun shared(shared: Boolean) = mutable.update { it.copy(shared = shared) }

    /** Moves to the next step, or creates the group from the last one. */
    fun next() {
        val state = mutable.value
        if (!state.canContinue) return
        when (state.step) {
            Step.DETAILS -> mutable.update { it.copy(step = Step.MEMBERS) }
            Step.MEMBERS -> mutable.update { it.copy(step = Step.STORAGE) }
            Step.STORAGE -> create(state)
        }
    }

    /** Goes back one step; false if this is the first, and the flow should close. */
    fun back(): Boolean {
        val previous =
            when (mutable.value.step) {
                Step.DETAILS -> return false
                Step.MEMBERS -> Step.DETAILS
                Step.STORAGE -> Step.MEMBERS
            }
        mutable.update { it.copy(step = previous, failed = false) }
        return true
    }

    /** Stores the group on this device; sharing it comes after (BE-1, DA-1). */
    private fun create(state: NewGroupState) {
        mutable.update { it.copy(creating = true, failed = false) }
        val names = listOf(state.you) + state.others.filter { it.isNotBlank() }
        val events =
            Group.create(
                id = state.id,
                name = state.name.trim(),
                description = state.description.trim().ifEmpty { null },
                emoji = state.emoji,
                colour = state.colour,
                members = names.map { Member(MemberId.parse(identifier()), it.trim()) },
                at = now(),
                ids = { EventId.parse(identifier()) },
            )
        viewModelScope.launch {
            val stored =
                withContext(Dispatchers.IO) {
                    try {
                        repository.create(events, today())
                        true
                    } catch (_: Malformed) {
                        // A name past the limits every reader holds to (format-spec 2.2).
                        false
                    }
                }
            mutable.update { it.copy(creating = false, failed = !stored, created = stored) }
        }
    }
}
