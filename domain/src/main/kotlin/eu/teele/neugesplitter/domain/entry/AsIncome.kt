package eu.teele.neugesplitter.domain.entry

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import kotlin.math.abs

/**
 * This expense restated as the income it stands for, with every member's balance unchanged.
 *
 * Splitwise has no incomes, so money one member received was recorded as an expense paid by the
 * others. The member left owing is the one who received the money; everyone else gets what they
 * were owed as their part of the income. Equal parts stay an equal split, any other split becomes
 * absolute amounts.
 *
 * @return null unless this is an expense that leaves exactly one member owing.
 */
fun Entry.asIncome(): Entry? {
    if (kind != Kind.EXPENSE) return null
    val before =
        try {
            contributions()
        } catch (_: Malformed) {
            return null
        }
    val owing = before.filterValues { it < Amount.ZERO }.keys
    if (owing.size != 1) return null
    val receiver = owing.single()
    val order = (beneficiaries.members + payers.members).distinct()
    val targets =
        order
            .associateWith { member ->
                val net = before[member] ?: Amount.ZERO
                if (member == receiver) net + amount else net
            }
            .filterValues { it > Amount.ZERO }
    if (targets.isEmpty() || targets.values.any { it > amount }) return null
    val tolerance = order.size.toLong()
    val units = targets.values.map { it.units }
    val share: Side =
        if (units.max() - units.min() <= tolerance) {
            Side.Equal(targets.keys.toList())
        } else {
            val others = Amount.sum(targets.filterKeys { it != receiver }.values)
            val exact = targets + (receiver to amount - others)
            if (exact.getValue(receiver) < Amount.ZERO) return null
            Side.Absolute(exact.filterValues { it > Amount.ZERO })
        }
    val income =
        copy(kind = Kind.INCOME, payers = Side.Equal(listOf(receiver)), beneficiaries = share)
    val after = income.contributions()
    val unchanged =
        (before.keys + after.keys).all { member: MemberId ->
            val was = (before[member] ?: Amount.ZERO).units
            val now = (after[member] ?: Amount.ZERO).units
            abs(was - now) <= tolerance
        }
    return if (unchanged) income else null
}
