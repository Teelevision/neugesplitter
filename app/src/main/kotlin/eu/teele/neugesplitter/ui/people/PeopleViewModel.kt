package eu.teele.neugesplitter.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.balance.Settlement
import eu.teele.neugesplitter.domain.balance.Transfer
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
 * @property people largest creditor first (BA-1).
 */
data class PeopleState(
    val loaded: Boolean = false,
    val title: String? = null,
    val scale: Scale = Scale.DEFAULT,
    val people: List<Person> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
) {
    private val names = people.associate { it.id to it.name }

    fun name(member: MemberId): String = names[member].orEmpty()
}

/** Who is in a group, what each of them is owed, and how to settle it (ui-design 4). */
class PeopleViewModel(repository: GroupRepository, group: GroupId, today: () -> EntryDate) :
    ViewModel() {

    val state: StateFlow<PeopleState> =
        repository
            .group(group)
            .map { log ->
                if (log == null) PeopleState(loaded = true)
                else people(log, repository.actingAs(group), today())
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleState())
}

/**
 * [group] as the people screen shows it to [me]. A removed member stays listed while they have a
 * balance or appear in an entry (GR-9).
 */
internal fun people(group: Group, me: MemberId?, today: EntryDate): PeopleState {
    val balances = group.settlement(today)
    val involved =
        (group.entries.values + group.occurrences(today).map { it.entry })
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
            .sortedWith(compareByDescending<Person> { it.balance }.thenBy { it.name })
    return PeopleState(
        loaded = true,
        title = listOfNotNull(group.emoji, group.name).joinToString(" "),
        scale = group.scale,
        people = people,
        transfers = Settlement.transfers(balances),
    )
}
