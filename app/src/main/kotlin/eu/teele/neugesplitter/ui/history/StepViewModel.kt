package eu.teele.neugesplitter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.category.Category
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.history.Happened
import eu.teele.neugesplitter.domain.history.Happening
import eu.teele.neugesplitter.domain.history.Settings
import eu.teele.neugesplitter.domain.history.history
import eu.teele.neugesplitter.domain.history.series
import eu.teele.neugesplitter.domain.history.steps
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.storage.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * @property step the step as its line tells it; null once loaded only if the log holds no such
 *   event.
 * @property parts what each event of [step] did, oldest first.
 * @property names every member of the group by name, removed ones too.
 * @property created the group's settings as it was created, for the step that created it.
 * @property entry the entry [step] names, if any.
 * @property entryKept whether [entry] is in the group now, not deleted.
 * @property series the series [step] names, while the group still holds it.
 */
data class StepState(
    val loaded: Boolean = false,
    val step: Happening? = null,
    val parts: List<Happening> = emptyList(),
    val scale: Scale = Scale.DEFAULT,
    val names: Map<MemberId, String> = emptyMap(),
    val categories: Map<CategoryId, Category> = emptyMap(),
    val created: Settings? = null,
    val entry: EntryId? = null,
    val entryKept: Boolean = false,
    val series: SeriesId? = null,
)

/**
 * The step of a group's history that [event] is part of, with each event it is told from (HI-10).
 */
class StepViewModel(repository: GroupRepository, group: GroupId, event: EventId) : ViewModel() {

    val state: StateFlow<StepState> =
        repository
            .log(group)
            .map { events ->
                val step =
                    steps(history(events)).firstOrNull { step ->
                        step.parts.any { it.event == event }
                    } ?: return@map StepState(loaded = true)
                val replayed = Group.replay(events)
                val happened = step.happening.happened
                val entry =
                    when (happened) {
                        is Happened.Entry -> happened.entry
                        is Happened.MadeRecurring -> happened.entry
                        else -> null
                    }
                StepState(
                    loaded = true,
                    step = step.happening,
                    parts = step.parts,
                    scale = replayed.scale,
                    names = replayed.members.mapValues { it.value.name },
                    categories = replayed.categories,
                    created =
                        if (happened == Happened.Created) Settings.of(Group.replay(events.take(1)))
                        else null,
                    entry = entry,
                    entryKept = entry != null && entry in replayed.entries,
                    series = happened.series?.takeIf { replayed.series[it]?.deleted == false },
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StepState())
}
