package eu.teele.neugesplitter.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Series
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.ListedEntry
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @property segment what the series does today, or did last before it stopped.
 * @property starts its first day, while that is still ahead.
 * @property stop where it stops: stopped if that is today or before, stopping if after (RE-7).
 * @property entries its occurrences in the entry list, newest first.
 */
data class SeriesDetail(
    val today: EntryDate,
    val segment: Segment,
    val payers: List<String>,
    val scale: Scale,
    val starts: EntryDate?,
    val stop: EntryDate?,
    val stopped: Boolean,
    val entries: List<ListedEntry>,
)

/** @property detail null once loaded only if the series is gone, deleted or with its group. */
data class SeriesState(val loaded: Boolean = false, val detail: SeriesDetail? = null)

/** One recurring series, and pausing, resuming, stopping and deleting it (RE-7, RE-9). */
class SeriesViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val series: SeriesId,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
) : ViewModel() {

    val state: StateFlow<SeriesState> =
        combine(repository.group(group), repository.overview(group, today())) { log, overview ->
                val listed = overview?.entries.orEmpty()
                SeriesState(loaded = true, detail = log?.detail(listed))
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesState())

    /** Stops it from tomorrow, so that today's stands. */
    fun pause() = write { id, by, at ->
        Event.SeriesEnded(id, by, at, series, EntryDate.of(today().value.plusDays(1)))
    }

    /** Stops it from [from] on; what it produced before stands (RE-7). */
    fun stop(from: EntryDate) = write { id, by, at -> Event.SeriesEnded(id, by, at, series, from) }

    /** Goes on from today, or from its stop if that is still ahead, as it was before it (RE-7). */
    fun resume() = write { id, by, at ->
        val (from, segment) = current()?.resumption(today()) ?: return@write null
        Event.SeriesAmended(id, by, at, series, from, segment)
    }

    /** Removes it and everything it produced (RE-7). The screen has asked first. */
    fun delete() = write { id, by, at -> Event.SeriesDeleted(id, by, at, series) }

    private fun current(): Series? = repository.load(group)?.series?.get(series)

    private fun write(event: (EventId, MemberId, Instant) -> Event?) {
        viewModelScope.launch(Dispatchers.IO) {
            val by = repository.actingAs(group) ?: return@launch
            val written = event(EventId.parse(identifier()), by, now()) ?: return@launch
            try {
                repository.append(group, listOf(written), today())
            } catch (_: Malformed) {
                // Deleted meanwhile, by someone whose deletion arrived first.
            }
        }
    }

    private fun Group.detail(listed: List<ListedEntry>): SeriesDetail? {
        val found = series[this@SeriesViewModel.series]?.takeUnless { it.deleted } ?: return null
        val today = today()
        val first = found.segments.minOf { it.first }
        val segment = found.current(today) ?: return null
        val stop = found.stop(today)
        val ids = found.occurrences(today).filterNot { it.pending }.map { it.entry.id }.toSet()
        return SeriesDetail(
            today = today,
            segment = segment,
            payers = segment.template.payers.members.map { members[it]?.name ?: it.hex.take(8) },
            scale = scale,
            starts = first.takeIf { it > today },
            stop = stop,
            stopped = stop != null && stop <= today,
            entries = listed.filter { it.id in ids },
        )
    }
}
