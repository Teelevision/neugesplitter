package eu.teele.neugesplitter.storage

import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale

/**
 * A group as its overview shows it (ui-design 4).
 *
 * @property own the settled balance of the member this installation acts as (BA-7), or null if it
 *   acts as none.
 * @property entries every entry that counts, newest first.
 * @property members the names of everyone the entries can name, removed members too, by name.
 */
data class Overview(
    val name: String,
    val emoji: String?,
    val colour: Colour?,
    val scale: Scale,
    val own: Amount?,
    val entries: List<ListedEntry>,
    val members: Map<MemberId, String> = emptyMap(),
) {
    /** What the group has spent: its expenses, and neither income nor transfers (EN-7). */
    val total: Amount
        get() =
            entries
                .filter { it.kind == Kind.EXPENSE }
                .fold(Amount.ZERO) { sum, it -> sum + it.amount }
}

/**
 * An entry as the overview lists it (ui-design 4).
 *
 * @property payers the names of whoever paid, in the order they were entered.
 * @property payerIds who paid, and [beneficiaryIds] whom it was for, to filter by (EN-12).
 * @property own what it does to the balance of the member this installation acts as: what they paid
 *   of it less what they owe of it, the other way round for an income (format-spec 7.1); null if
 *   they are on neither side.
 * @property repeats whether it is an occurrence of a series (RE-9).
 * @property edited whether it was changed after it was created (HI-4).
 * @property unseen whether another installation changed it and that has not been seen yet (HI-5).
 */
data class ListedEntry(
    val id: EntryId,
    val kind: Kind,
    val description: String,
    val date: EntryDate,
    val amount: Amount,
    val payers: List<String>,
    val own: Amount?,
    val repeats: Boolean,
    val edited: Boolean,
    val unseen: Boolean,
    val payerIds: Set<MemberId> = emptySet(),
    val beneficiaryIds: Set<MemberId> = emptySet(),
)
