package eu.teele.neugesplitter.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.category.Category
import eu.teele.neugesplitter.domain.event.CategoryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.storage.GroupRepository
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategorySettingsState(
    val loaded: Boolean = false,
    val categories: List<Category> = emptyList(),
)

class CategorySettingsViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    val state: StateFlow<CategorySettingsState> =
        repository
            .group(group)
            .map { loaded ->
                CategorySettingsState(
                    loaded = true,
                    categories = loaded?.categories?.values?.toList().orEmpty(),
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategorySettingsState())

    fun create(name: String, emoji: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || emoji.isBlank()) return
        write { by, at ->
            Event.CategoryCreated(
                EventId.parse(identifier()),
                by,
                at,
                Category(id = CategoryId.parse(identifier()), name = trimmed, emoji = emoji),
            )
        }
    }

    fun update(id: CategoryId, name: String, emoji: String, localizedName: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || emoji.isBlank()) return
        write { by, at ->
            val current = repository.load(group)?.categories?.get(id) ?: return@write null
            val sharedName =
                if (current.builtin != null && trimmed == localizedName) null else trimmed
            val after = current.copy(name = sharedName, emoji = emoji)
            val changes = CategoryChanges.between(current, after)
            if (changes.isEmpty) null
            else Event.CategoryUpdated(EventId.parse(identifier()), by, at, id, changes)
        }
    }

    fun delete(id: CategoryId) = write { by, at ->
        val category = repository.load(group)?.categories?.get(id) ?: return@write null
        if (category.deleted) null
        else Event.CategoryDeleted(EventId.parse(identifier()), by, at, id)
    }

    fun restore(id: CategoryId) = write { by, at ->
        val category = repository.load(group)?.categories?.get(id) ?: return@write null
        if (!category.deleted) null
        else Event.CategoryRestored(EventId.parse(identifier()), by, at, id)
    }

    private fun write(create: (eu.teele.neugesplitter.domain.value.MemberId, Instant) -> Event?) {
        (scope ?: viewModelScope).launch(Dispatchers.IO) {
            val by = repository.actingAs(group) ?: return@launch
            val event = create(by, now()) ?: return@launch
            repository.append(group, listOf(event), today())
        }
    }
}
