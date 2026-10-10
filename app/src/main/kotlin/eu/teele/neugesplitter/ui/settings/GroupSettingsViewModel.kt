package eu.teele.neugesplitter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Stop
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.SyncState
import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @property loaded false until the group has been read once.
 * @property title the group's emoji and name; null once loaded only if this device no longer holds
 *   the group.
 * @property members those not removed, in the order they joined.
 * @property defaultParticipants who a new entry starts with, without the removed; null for everyone
 *   (SP-8).
 * @property sync how the group's sync stands; null for a group kept only on this device.
 * @property series the recurring series that run, and below them those that stopped (RE-7), by
 *   description.
 */
data class GroupSettingsState(
    val loaded: Boolean = false,
    val title: String? = null,
    val name: String = "",
    val description: String? = null,
    val emoji: String? = null,
    val colour: Colour? = null,
    val members: List<Member> = emptyList(),
    val defaultParticipants: List<MemberId>? = null,
    val backend: Shared? = null,
    val sync: SyncState? = null,
    val series: List<SeriesSummary> = emptyList(),
    val stopped: List<SeriesSummary> = emptyList(),
)

/**
 * A series as the settings list it: what it is, how it repeats, and the stop in force today or else
 * the next one ahead (RE-7).
 */
data class SeriesSummary(
    val id: SeriesId,
    val description: String,
    val interval: Interval,
    val stop: Stop?,
    /** Whether [stop] holds it today. */
    val held: Boolean,
)

/**
 * Where a shared group is kept, whether it is encrypted there (PR-11), and the shortest wait
 * between its background polls (SY-13).
 */
data class Shared(
    val host: String,
    val bucket: String,
    val encrypted: Boolean,
    val interval: Duration,
)

/** What a group is called and looks like, who entries start with, and where it is kept. */
class GroupSettingsViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    /** Stops syncing a deleted group and forgets its secrets. */
    private val forget: (GroupId) -> Unit = {},
    /** Has a group polled at most this often (SY-13). */
    private val setInterval: (GroupId, Duration) -> Unit = { _, _ -> },
) : ViewModel() {

    val state: StateFlow<GroupSettingsState> =
        combine(
                repository.group(group),
                repository.backends.state(group),
                repository.backends.interval(group),
            ) { log, sync, interval ->
                if (log == null) return@combine GroupSettingsState(loaded = true)
                val shared =
                    sync?.let {
                        repository.backends.settings(group)?.let { at ->
                            Shared(
                                URI(at.endpoint).host ?: at.endpoint,
                                at.bucket,
                                repository.backends.root(group) != null,
                                interval ?: return@let null,
                            )
                        }
                    }
                settings(log, today()).copy(backend = shared, sync = sync)
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupSettingsState())

    /** Writes nothing if the name is blank or is the one the group has (GR-2). */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == state.value.name) return
        write(GroupChanges(name = Change.To(trimmed)))
    }

    /** A blank description is none. Writes nothing if it is the one the group has (GR-2). */
    fun describe(description: String) {
        val trimmed = description.trim().ifEmpty { null }
        if (trimmed == state.value.description) return
        write(GroupChanges(description = Change.To(trimmed)))
    }

    /**
     * Chooses [emoji], or clears it if it was the one chosen, as the new-group flow does (GR-2).
     */
    fun emoji(emoji: String) {
        val chosen = emoji.takeIf { state.value.emoji != it }
        write(GroupChanges(emoji = Change.To(chosen)))
    }

    /** A group always has a colour, so choosing the one it has writes nothing (GR-2). */
    fun colour(colour: Colour) {
        if (colour == state.value.colour) return
        write(GroupChanges(colour = Change.To(colour)))
    }

    /**
     * Starts new entries with [chosen] of the members. All of them is stored as everyone, so that
     * members added later are included (SP-8). Writes nothing for none, or for what the group has.
     */
    fun defaultParticipants(chosen: Set<MemberId>) {
        val current = state.value
        val picked = current.members.map { it.id }.filter { it in chosen }
        if (picked.isEmpty()) return
        val change = picked.takeIf { it.size < current.members.size }
        val before = current.defaultParticipants
        if (
            change == before ||
                (change != null && before != null && change.toSet() == before.toSet())
        )
            return
        write(GroupChanges(defaultParticipants = Change.To(change)))
    }

    /** Polls at most every [interval] (SY-13); changes nothing for the one the group has. */
    fun interval(interval: Duration) {
        if (interval == state.value.backend?.interval) return
        viewModelScope.launch(Dispatchers.IO) { setInterval(group, interval) }
    }

    /** Removes the group from this device (GR-11), then runs [then]. The screen has asked first. */
    fun delete(then: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                forget(group)
                repository.delete(group)
            }
            then()
        }
    }

    private fun write(changes: GroupChanges) {
        viewModelScope.launch(Dispatchers.IO) {
            val by = repository.actingAs(group) ?: return@launch
            val event = Event.GroupUpdated(EventId.parse(identifier()), by, now(), changes)
            repository.append(group, listOf(event), today())
        }
    }
}

private fun settings(group: Group, today: EntryDate): GroupSettingsState {
    val (running, stopped) =
        group.series.values
            .filterNot { it.deleted }
            .map { series ->
                val segment = series.current(today)
                val stop = series.stop(today)
                SeriesSummary(
                    series.id,
                    segment.template.description,
                    segment.interval,
                    stop,
                    held = stop != null && today in stop,
                )
            }
            .sortedBy { it.description.lowercase() }
            // Stopped for good as of today; a pause goes on by itself.
            .partition { !it.held || it.stop?.until != null }
    return GroupSettingsState(
        loaded = true,
        title = listOfNotNull(group.emoji, group.name).joinToString(" "),
        name = group.name,
        description = group.description,
        emoji = group.emoji,
        colour = group.colour,
        members = group.members.values.filter { it.id !in group.removed },
        defaultParticipants = group.defaultParticipants?.filter { it !in group.removed },
        series = running,
        stopped = stopped,
    )
}
