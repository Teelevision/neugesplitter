package eu.teele.neugesplitter.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Series
import eu.teele.neugesplitter.domain.recurrence.Stop
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
 * @property segment what the series does today, or on its first day while that is ahead.
 * @property entries its occurrences in the entry list, newest first.
 */
data class SeriesDetail(
    val today: EntryDate,
    val series: Series,
    val segment: Segment,
    val payers: List<String>,
    val scale: Scale,
    val entries: List<ListedEntry>,
) {
    /** Its first day, while that is still ahead. */
    val starts: EntryDate?
        get() = series.start.takeIf { it > today }

    /** The stop in force today, or else the next one ahead (RE-7). */
    val stop: Stop?
        get() = series.stop(today)

    /** Whether it produces nothing today. */
    val stopped: Boolean
        get() = stop?.let { today in it } == true

    /** Whether it is stopped for good as of today, so that only continuing it is left. */
    val ended: Boolean
        get() = stopped && stop?.until == null

    /** The earliest it can stop: after its first occurrence, as stopping before is deleting it. */
    val earliestStop: EntryDate
        get() = (series.nextDue(series.start) ?: series.start).plusDays(1)

    /** Stopping offers today, or the earliest it can. */
    val stopFrom: EntryDate
        get() = maxOf(today, earliestStop)

    /** A pause offers today, or its first day while that is ahead. */
    val pauseFrom: EntryDate
        get() = maxOf(today, series.start)

    /** A pause from [from] offers to skip one occurrence: it continues the day after the next. */
    fun pauseUntil(from: EntryDate): EntryDate = (series.nextDue(from) ?: from).plusDays(1)

    /**
     * How many entries in the balances today [revised] would remove, and how many it would add;
     * those somebody confirmed or edited stand either way (format-spec 4.2).
     */
    fun change(revised: Series): Pair<Int, Int> {
        val before = series.occurrences(today).filterNot { it.pending }.map { it.due }.toSet()
        val after = revised.occurrences(today).filterNot { it.pending }.map { it.due }.toSet()
        return (before - after).size to (after - before).size
    }
}

private fun EntryDate.plusDays(days: Long) = EntryDate.of(value.plusDays(days))

/** @property detail null once loaded only if the series is gone, deleted or with its group. */
data class SeriesState(val loaded: Boolean = false, val detail: SeriesDetail? = null)

/** One recurring series, and stopping, pausing, continuing and deleting it (RE-7, RE-9). */
class SeriesViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val series: SeriesId,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
) : ViewModel() {

    val state: StateFlow<SeriesState> =
        combine(repository.group(group), repository.overview(group, today)) { log, overview ->
                val listed = overview?.entries.orEmpty()
                SeriesState(loaded = true, detail = log?.detail(listed))
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesState())

    /** Stops it for good from [from] on; what it produced before stands (RE-7). */
    fun stop(from: EntryDate) = write { id, by, at ->
        Event.SeriesStopped(id, by, at, series, from)
    }

    /** Stops it from [from] and has it continue on [until] by itself (RE-7). */
    fun pause(from: EntryDate, until: EntryDate) = write { id, by, at ->
        Event.SeriesStopped(id, by, at, series, from, until)
    }

    /** Has it go on from [from] where its stop in force or next ahead would have held it (RE-7). */
    fun resume(from: EntryDate) = write { id, by, at ->
        val stop = current()?.stop(today()) ?: return@write null
        Event.SeriesContinued(id, by, at, series, from, stop.until)
    }

    /** Takes [stop] back as a whole, past ones too; what it held back is due again (RE-7). */
    fun remove(stop: Stop) = write { id, by, at ->
        Event.SeriesContinued(id, by, at, series, stop.from, stop.until)
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
        val segment = found.current(today)
        val ids = found.occurrences(today).filterNot { it.pending }.map { it.entry.id }.toSet()
        return SeriesDetail(
            today = today,
            series = found,
            segment = segment,
            payers = segment.template.payers.members.map { members[it]?.name ?: it.hex.take(8) },
            scale = scale,
            entries = listed.filter { it.id in ids },
        )
    }
}
