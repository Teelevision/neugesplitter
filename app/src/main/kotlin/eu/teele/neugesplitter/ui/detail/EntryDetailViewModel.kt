package eu.teele.neugesplitter.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** One member's part of one side, unrounded (SP-7). */
data class Part(val name: String, val amount: Amount)

data class Detail(
    val entry: Entry,
    val scale: Scale,
    val payers: List<Part>,
    val beneficiaries: List<Part>,
    val author: String?,
    val edited: Boolean,
)

/** @property detail null once loaded only if the entry is gone, deleted or with its group. */
data class EntryDetailState(val loaded: Boolean = false, val detail: Detail? = null)

/** One entry; while it is shown, every change to it counts as seen (HI-6). */
class EntryDetailViewModel(repository: GroupRepository, group: GroupId, entry: EntryId) :
    ViewModel() {

    val state: StateFlow<EntryDetailState> =
        repository
            .group(group)
            .map { EntryDetailState(loaded = true, detail = it?.detail(entry)) }
            .onEach { if (it.detail != null) repository.seen(group, entry) }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryDetailState())

    private fun Group.detail(id: EntryId): Detail? {
        val entry = entries[id] ?: return null
        fun parts(side: Side) =
            side.parts(entry.amount).map { (member, part) ->
                Part(members.getValue(member).name, part)
            }
        val authorship = authors[id]
        return Detail(
            entry = entry,
            scale = scale,
            payers = parts(entry.payers),
            beneficiaries = parts(entry.beneficiaries),
            author = authorship?.let { members[it.by]?.name },
            edited = authorship?.edited == true,
        )
    }
}
