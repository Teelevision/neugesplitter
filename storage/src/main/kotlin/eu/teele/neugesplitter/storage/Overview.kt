package eu.teele.neugesplitter.storage

import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
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
 * @property entries every entry that counts, newest first; only the newest of them unless
 *   [complete].
 * @property members the names of everyone the entries can name, removed members too, by name.
 * @property total what the group has spent: its expenses, and neither income nor transfers (EN-7).
 */
data class Overview(
    val name: String,
    val emoji: String?,
    val colour: Colour?,
    val scale: Scale,
    val own: Amount?,
    val entries: List<ListedEntry>,
    val members: Map<MemberId, String> = emptyMap(),
    val total: Amount = Amount.ZERO,
    val complete: Boolean = true,
)

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
 * @property unsynced whether a change to it is not on the group's backend yet (SY-16).
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
    val categoryEmoji: String? = null,
    val categoryId: CategoryId? = null,
    val unsynced: Boolean = false,
)

/**
 * A group as its people screen shows it (ui-design 4).
 *
 * @property members everyone, removed members too, in the order they were added.
 */
data class People(
    val name: String,
    val emoji: String?,
    val scale: Scale,
    val members: List<ListedMember>,
)

/**
 * A member with their settled balance (BA-2), and whether an entry that counts or is offered names
 * them, which keeps a removed member listed (GR-9).
 */
data class ListedMember(
    val id: MemberId,
    val name: String,
    val removed: Boolean,
    val balance: Amount,
    val involved: Boolean,
)
