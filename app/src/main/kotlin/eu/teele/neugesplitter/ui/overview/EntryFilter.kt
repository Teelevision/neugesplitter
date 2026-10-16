package eu.teele.neugesplitter.ui.overview

import eu.teele.neugesplitter.domain.calculator.amountIn
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.ListedEntry

/**
 * What the overview's entries are narrowed to (EN-12). Every part is optional, and an occurrence is
 * matched on its own, not with its series.
 *
 * @property text a part of the description, in any case.
 * @property least the smallest amount, as typed; and [most] the largest. Both count.
 * @property payers members who all paid; and [beneficiaries] members it was all for.
 * @property from the first date; and [until] the last. Both count.
 */
data class EntryFilter(
    val text: String = "",
    val least: String = "",
    val most: String = "",
    val kind: Kind? = null,
    val payers: Set<MemberId> = emptySet(),
    val beneficiaries: Set<MemberId> = emptySet(),
    val from: EntryDate? = null,
    val until: EntryDate? = null,
) {
    val active: Boolean
        get() = copy(text = text.trim(), least = least.trim(), most = most.trim()) != EntryFilter()

    /** Those of [entries] that match; an amount that cannot be read at [scale] bounds nothing. */
    fun matching(entries: List<ListedEntry>, scale: Scale): List<ListedEntry> {
        if (!active) return entries
        val words = text.trim()
        val low = amountIn(least, scale)?.roundedTo(scale)
        val high = amountIn(most, scale)?.roundedTo(scale)
        return entries.filter {
            it.description.contains(words, ignoreCase = true) &&
                (low == null || it.amount >= low) &&
                (high == null || it.amount <= high) &&
                (kind == null || it.kind == kind) &&
                it.payerIds.containsAll(payers) &&
                it.beneficiaryIds.containsAll(beneficiaries) &&
                (from == null || it.date >= from) &&
                (until == null || it.date <= until)
        }
    }
}
