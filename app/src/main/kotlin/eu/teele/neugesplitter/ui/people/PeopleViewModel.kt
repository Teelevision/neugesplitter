package eu.teele.neugesplitter.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.balance.Settlement
import eu.teele.neugesplitter.domain.balance.Transfer
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.settings.SyncState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A member as the people screen lists them, with their settled balance (BA-1). */
data class Person(
    val id: MemberId,
    val name: String,
    val balance: Amount,
    val removed: Boolean,
    val me: Boolean,
)

/**
 * @property loaded false until the group has been read once.
 * @property title the group's emoji and name; null once loaded only if this device no longer holds
 *   the group.
 * @property people in the order the members were added to the group.
 * @property sync how the group's sync stands; null for a group kept only on this device.
 */
data class PeopleState(
    val loaded: Boolean = false,
    val title: String? = null,
    val scale: Scale = Scale.DEFAULT,
    val people: List<Person> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val sync: SyncState? = null,
) {
    private val names = people.associate { it.id to it.name }

    fun name(member: MemberId): String = names[member].orEmpty()
}

/** Who is in a group, what each of them is owed, and how to settle it (ui-design 4). */
class PeopleViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
) : ViewModel() {

    val state: StateFlow<PeopleState> =
        combine(repository.group(group), repository.backends.state(group)) { log, sync ->
                if (log == null) PeopleState(loaded = true)
                else people(log, repository.actingAs(group), today()).copy(sync = sync)
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleState())

    /** Adds a member (GR-5). A name that is blank is not one (the new-group rules). */
    fun add(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        write { by ->
            Event.MemberAdded(
                EventId.parse(identifier()),
                by,
                now(),
                Member(MemberId.parse(identifier()), trimmed),
            )
        }
    }

    /** Writes nothing if the name is blank or is the one the member has (ID-2). */
    fun rename(member: MemberId, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || state.value.name(member) == trimmed) return
        write { by -> Event.MemberRenamed(EventId.parse(identifier()), by, now(), member, trimmed) }
    }

    /** Only a member who is not yet removed and is not the one this installation acts as (GR-6). */
    fun remove(member: MemberId) {
        val person = state.value.people.firstOrNull { it.id == member } ?: return
        if (person.me || person.removed) return
        write { by -> Event.MemberRemoved(EventId.parse(identifier()), by, now(), member) }
    }

    private fun write(event: (by: MemberId) -> Event) {
        viewModelScope.launch(Dispatchers.IO) {
            val by = repository.actingAs(group) ?: return@launch
            repository.append(group, listOf(event(by)), today())
        }
    }
}

/**
 * [group] as the people screen shows it to [me]. A removed member stays listed while they have a
 * balance or appear in an entry (GR-9).
 */
internal fun people(group: Group, me: MemberId?, today: EntryDate): PeopleState {
    val balances = group.settlement(today)
    val occurrences = group.occurrences(today)
    val involved =
        (group.entries.values + occurrences.map { it.entry })
            .flatMap { it.payers.members + it.beneficiaries.members }
            .toSet()
    val people =
        group.members.values
            .filter {
                it.id !in group.removed || it.id in involved || !balances.getValue(it.id).isZero
            }
            .map {
                Person(
                    id = it.id,
                    name = it.name,
                    balance = balances.getValue(it.id),
                    removed = it.id in group.removed,
                    me = it.id == me,
                )
            }
    return PeopleState(
        loaded = true,
        title = listOfNotNull(group.emoji, group.name).joinToString(" "),
        scale = group.scale,
        people = people,
        transfers = Settlement.transfers(balances),
    )
}
