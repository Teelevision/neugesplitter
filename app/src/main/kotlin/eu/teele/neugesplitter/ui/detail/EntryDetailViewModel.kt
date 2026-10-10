package eu.teele.neugesplitter.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.editor.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** One member's part of one side, unrounded (SP-7). */
data class Part(val name: String, val amount: Amount)

/**
 * @property series the series the entry came from, if it did (RE-9).
 * @property recurrence how that series repeats on the occurrence's due date; null where it no
 *   longer does.
 */
data class Detail(
    val entry: Entry,
    val scale: Scale,
    val payers: List<Part>,
    val beneficiaries: List<Part>,
    val author: String?,
    val edited: Boolean,
    val series: SeriesId? = null,
    val recurrence: Recurrence? = null,
)

/** @property detail null once loaded only if the entry is gone, deleted or with its group. */
data class EntryDetailState(val loaded: Boolean = false, val detail: Detail? = null)

/**
 * One entry, an occurrence due by [today] included; while it is shown, every change to it counts as
 * seen (HI-6).
 */
class EntryDetailViewModel(
    repository: GroupRepository,
    group: GroupId,
    entry: EntryId,
    private val today: () -> EntryDate,
) : ViewModel() {

    val state: StateFlow<EntryDetailState> =
        repository
            .group(group)
            .map { EntryDetailState(loaded = true, detail = it?.detail(entry)) }
            .onEach { if (it.detail != null) repository.seen(group, entry) }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryDetailState())

    private fun Group.detail(id: EntryId): Detail? {
        val occurrence = if (id in entries) null else occurrence(id, today())
        val entry = entries[id] ?: occurrence?.entry ?: return null
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
            series = occurrence?.series,
            recurrence =
                occurrence
                    ?.let { series[it.series]?.segment(it.due) }
                    ?.let { Recurrence(it.interval, it.mode) },
        )
    }
}
