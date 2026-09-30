package eu.teele.neugesplitter.domain.balance

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale

/**
 * Rounding balances for display and settlement (format-spec 7.1).
 *
 * Exact balances sum to zero at 10⁻⁸ only up to the per-member shortfall left by truncation in
 * format-spec 7, and rounding to the group's scale adds a residue of its own. Both are absorbed by
 * the creditors, so that debtors owe exactly what they are shown.
 */
object Settlement {

    /**
     * The balances of [balances] rounded to [scale], with the residue absorbed so that they sum to
     * zero (BA-2).
     *
     * [balances] are the exact balances in 10⁻⁸ units. The result is too, but every value is a
     * multiple of [Scale.unit].
     */
    fun rounded(balances: Map<MemberId, Amount>, scale: Scale): Map<MemberId, Amount> {
        val rounded = balances.mapValues { (_, balance) -> balance.roundedTo(scale) }
        val residue = Amount.sum(rounded.values)
        if (residue.isZero) return rounded

        val order =
            balances.entries
                .sortedWith(
                    compareByDescending<Map.Entry<MemberId, Amount>> { it.value }.thenBy { it.key }
                )
                .map { it.key }
        val creditors = order.filter { balances.getValue(it).isPositive }
        val absorbers = creditors.ifEmpty { order }

        val unit = Amount.ofUnits(scale.unit)
        val steps = residue.units / scale.unit
        val settled = rounded.toMutableMap()
        if (steps < 0) {
            // Too little was handed out, so the creditors are given a unit each in turn.
            repeat(-steps.toInt()) { i ->
                val member = absorbers[i % absorbers.size]
                settled[member] = settled.getValue(member) + unit
            }
        } else {
            // Too much was handed out, so the creditors give a unit back in turn, skipping anyone
            // already at zero: a member in credit is never shown owing money.
            var remaining = steps
            while (remaining > 0) {
                var moved = false
                for (member in absorbers) {
                    if (remaining == 0L) break
                    if (!settled.getValue(member).isPositive) continue
                    settled[member] = settled.getValue(member) - unit
                    remaining--
                    moved = true
                }
                check(moved) { "residue $residue exceeds what the creditors hold: $settled" }
            }
        }
        return settled
    }
}
