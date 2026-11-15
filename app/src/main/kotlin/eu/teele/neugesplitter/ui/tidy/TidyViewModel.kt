package eu.teele.neugesplitter.ui.tidy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.category.Category
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.IncomeWords
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.asIncome
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.history.Happening
import eu.teele.neugesplitter.domain.history.history
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.storage.Proposal
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An expense that reads like an income, [before] and as the income it stands for [after]. */
data class IncomeItem(val before: Entry, val after: Entry)

/** Income candidates whose descriptions read the same, newest first (TI-2). */
data class IncomeBatch(val key: String, val items: List<IncomeItem>)

/**
 * Uncategorised entries whose descriptions read the same, newest first (TI-3).
 *
 * @property suggested the group's categories the model finds likeliest, likeliest first.
 * @property guessed the category the model is sure enough of to choose it beforehand.
 */
data class CategoryBatch(
    val key: String,
    val entries: List<Entry>,
    val suggested: List<CategoryId>,
    val guessed: CategoryId?,
)

/**
 * Changes to a shared group waiting to be confirmed (TI-5), and [happenings], the steps the group's
 * activity will show for them. [declined] are left out of [proposal] once confirmed.
 */
data class Preview(
    val proposal: Proposal,
    val changes: List<Pair<Entry, Entry>>,
    val declined: List<EntryId>,
    val happenings: List<Happening>,
)

/**
 * @property shared whether the group is shared, so that changes are confirmed first (TI-5).
 * @property excluded the entries unticked in their batch.
 * @property chosen each category batch's category, where the user chose one.
 * @property applied how many entries the last change changed, until shown.
 */
data class TidyState(
    val loaded: Boolean = false,
    val names: Map<MemberId, String> = emptyMap(),
    val scale: Scale = Scale.of(2),
    val categories: List<Category> = emptyList(),
    val shared: Boolean = false,
    val incomes: List<IncomeBatch> = emptyList(),
    val uncategorised: List<CategoryBatch> = emptyList(),
    val excluded: Set<EntryId> = emptySet(),
    val chosen: Map<String, CategoryId> = emptyMap(),
    val preview: Preview? = null,
    val applied: Int? = null,
) {
    fun choice(batch: CategoryBatch): CategoryId? = chosen[batch.key] ?: batch.guessed
}

private val NOISE = Regex("[\\p{N}\\p{P}\\p{S}]+")
private val SPACES = Regex("\\s+")

/** [description] without case, digits and punctuation, which batches entries together. */
internal fun normalised(description: String): String =
    description.lowercase().replace(NOISE, " ").trim().replace(SPACES, " ").ifEmpty {
        description.trim().lowercase()
    }

/** The expenses of [group] that read like and can be restated as incomes (TI-2). */
internal fun incomes(group: Group, dismissed: Set<EntryId>): List<IncomeBatch> =
    group.entries.values
        .asSequence()
        .filter { it.kind == Kind.EXPENSE && it.id !in dismissed }
        .filter { IncomeWords.match(it.description) }
        .mapNotNull { entry -> entry.asIncome()?.let { IncomeItem(entry, it) } }
        .groupBy { normalised(it.before.description) }
        .map { (key, items) -> IncomeBatch(key, items.sortedByDescending { it.before.date }) }
        .sortedWith(compareByDescending<IncomeBatch> { it.items.size }.thenBy { it.key })

/** The entries of [group] without a category, with what the model suggests for them (TI-3). */
internal fun uncategorised(
    group: Group,
    dismissed: Set<EntryId>,
    rank: (String) -> List<BuiltInCategory>,
    guess: (String) -> BuiltInCategory?,
): List<CategoryBatch> {
    fun available(category: BuiltInCategory?): CategoryId? =
        category?.id?.takeIf { group.categories[it]?.deleted == false }
    return group.entries.values
        .filter { it.categoryId == null && it.kind != Kind.TRANSFER && it.id !in dismissed }
        .groupBy { normalised(it.description) }
        .map { (key, entries) ->
            val newest = entries.sortedByDescending { it.date }
            val description = newest.first().description
            CategoryBatch(
                key,
                newest,
                rank(description).mapNotNull(::available),
                available(guess(description)),
            )
        }
        .sortedWith(
            compareBy<CategoryBatch> { it.guessed == null }
                .thenByDescending { it.entries.size }
                .thenBy { it.key }
        )
}

/**
 * Tidying up a group's history, imported ones above all (TI-1): expenses that stand for incomes,
 * and entries without a category, proposed in batches for the user to confirm.
 */
class TidyViewModel(
    private val repository: GroupRepository,
    private val group: GroupId,
    private val identifier: () -> String,
    private val today: () -> EntryDate,
    private val now: () -> Instant,
    private val rank: (String) -> List<BuiltInCategory>,
    private val guess: (String) -> BuiltInCategory?,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private data class Candidates(
        val group: Group?,
        val shared: Boolean,
        val incomes: List<IncomeBatch>,
        val uncategorised: List<CategoryBatch>,
    )

    private data class Selection(
        val excluded: Set<EntryId> = emptySet(),
        val chosen: Map<String, CategoryId> = emptyMap(),
        val preview: Preview? = null,
        val applied: Int? = null,
    )

    private val selection = MutableStateFlow(Selection())

    private val candidates =
        combine(
                repository.group(group),
                repository.dismissed(group, Proposal.INCOME),
                repository.dismissed(group, Proposal.CATEGORY),
                repository.backends.state(group).map { it != null }.distinctUntilChanged(),
            ) { loaded, noIncome, noCategory, shared ->
                Candidates(
                    loaded,
                    shared,
                    loaded?.let { incomes(it, noIncome) }.orEmpty(),
                    loaded?.let { uncategorised(it, noCategory, rank, guess) }.orEmpty(),
                )
            }
            .flowOn(Dispatchers.IO)

    val state: StateFlow<TidyState> =
        combine(candidates, selection) { candidates, selection ->
                val loaded = candidates.group
                TidyState(
                    loaded = true,
                    names = loaded?.members?.mapValues { it.value.name }.orEmpty(),
                    scale = loaded?.scale ?: Scale.of(2),
                    categories = loaded?.categories?.values?.filter { !it.deleted }.orEmpty(),
                    shared = candidates.shared,
                    incomes = candidates.incomes,
                    uncategorised = candidates.uncategorised,
                    excluded = selection.excluded,
                    chosen = selection.chosen,
                    preview = selection.preview,
                    applied = selection.applied,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TidyState())

    /** Ticks [entry] in its batch, or unticks it. */
    fun toggle(entry: EntryId) =
        selection.update {
            it.copy(
                excluded = if (entry in it.excluded) it.excluded - entry else it.excluded + entry
            )
        }

    fun choose(batch: CategoryBatch, category: CategoryId) =
        selection.update { it.copy(chosen = it.chosen + (batch.key to category)) }

    /** Leaves every entry of a batch as it is and out of [proposal] from now on (TI-4). */
    fun dismiss(proposal: Proposal, entries: List<EntryId>) {
        (scope ?: viewModelScope).launch(Dispatchers.IO) {
            repository.dismiss(group, proposal, entries)
        }
    }

    /** Restates the ticked entries of [batch] as incomes; the unticked are not incomes (TI-2). */
    fun makeIncome(batch: IncomeBatch) {
        val excluded = selection.value.excluded
        val (ticked, unticked) = batch.items.partition { it.before.id !in excluded }
        propose(
            Proposal.INCOME,
            ticked.map { it.before to it.after },
            unticked.map { it.before.id },
        )
    }

    /** Gives the ticked entries of [batch] its chosen category (TI-3). */
    fun categorise(batch: CategoryBatch) {
        val category = state.value.choice(batch) ?: return
        val excluded = selection.value.excluded
        propose(
            Proposal.CATEGORY,
            batch.entries
                .filter { it.id !in excluded }
                .map { it to it.copy(categoryId = category) },
            emptyList(),
        )
    }

    /** Writes the changes waiting for confirmation (TI-5). */
    fun confirm() {
        val preview = selection.value.preview ?: return
        selection.update { it.copy(preview = null) }
        write(preview.proposal, preview.changes, preview.declined)
    }

    fun cancel() = selection.update { it.copy(preview = null) }

    fun appliedTaken() = selection.update { it.copy(applied = null) }

    /** Writes [changes] at once in a group kept here, and shows them first in a shared one. */
    private fun propose(
        proposal: Proposal,
        changes: List<Pair<Entry, Entry>>,
        declined: List<EntryId>,
    ) {
        if (changes.isEmpty() || !state.value.shared) {
            write(proposal, changes, declined)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val by = repository.actingAs(group) ?: return@launch
            val proposed = updates(changes, by, now())
            val happenings = history(repository.events(group) + proposed).takeLast(proposed.size)
            selection.update { it.copy(preview = Preview(proposal, changes, declined, happenings)) }
        }
    }

    /**
     * Writes [changes] in one go, each only if its entry is still as it was proposed from, so that
     * nothing changed or deleted meanwhile is overwritten or brought back.
     */
    private fun write(
        proposal: Proposal,
        changes: List<Pair<Entry, Entry>>,
        declined: List<EntryId>,
    ) {
        (scope ?: viewModelScope).launch(Dispatchers.IO) {
            val by = repository.actingAs(group) ?: return@launch
            val current = repository.load(group) ?: return@launch
            val fresh = changes.filter { (before, _) -> current.entries[before.id] == before }
            if (fresh.isNotEmpty()) repository.append(group, updates(fresh, by, now()), today())
            if (declined.isNotEmpty()) repository.dismiss(group, proposal, declined)
            val done = changes.map { it.first.id } + declined
            selection.update {
                it.copy(
                    excluded = it.excluded - done.toSet(),
                    applied = fresh.size.takeIf { size -> size > 0 },
                )
            }
        }
    }

    private fun updates(changes: List<Pair<Entry, Entry>>, by: MemberId, at: Instant) =
        changes.map { (before, after) ->
            Event.EntryUpdated(
                EventId.parse(identifier()),
                by,
                at,
                before.id,
                EntryChanges.between(before, after),
            )
        }
}
