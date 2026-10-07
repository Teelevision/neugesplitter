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

    /**
     * Transfers that bring every one of [balances] to zero, as few as there can be (BA-3, BA-4).
     *
     * Members with a balance can be settled in groups whose balances sum to zero, each needing one
     * transfer fewer than it has members, so the fewest transfers come from the most such groups.
     * Finding them is exponential in the number of members with a balance, so above [EXACT] of them
     * the balances are settled as one group: still never more than one transfer fewer than members.
     *
     * Within a group the largest debtor pays the largest creditor, ties going to the lower member
     * identifier. The result is ordered by amount, largest first.
     *
     * @param balances sums to zero, as [rounded] does.
     */
    fun transfers(balances: Map<MemberId, Amount>): List<Transfer> {
        require(Amount.sum(balances.values).isZero) { "balances do not sum to zero: $balances" }
        val open =
            balances.entries
                .filterNot { it.value.isZero }
                .sortedWith(BY_BALANCE)
                .map { it.key to it.value }
        val groups = if (open.size <= EXACT) groups(open) else listOf(open)
        return groups
            .flatMap(::settled)
            .sortedWith(
                compareByDescending<Transfer> { it.amount }.thenBy { it.from }.thenBy { it.to }
            )
    }

    /** The most members with a balance for which [transfers] still finds the fewest transfers. */
    const val EXACT: Int = 16

    private val BY_BALANCE =
        compareByDescending<Map.Entry<MemberId, Amount>> { it.value }.thenBy { it.key }

    /** [open] split into as many groups summing to zero as it can be. */
    private fun groups(open: List<Pair<MemberId, Amount>>): List<List<Pair<MemberId, Amount>>> {
        val size = 1 shl open.size
        val sums = LongArray(size)
        // most[mask]: how many groups summing to zero the members in mask can be split into, the
        // last one possibly not summing to zero.
        val most = IntArray(size)
        for (mask in 1 until size) {
            val lowest = Integer.numberOfTrailingZeros(mask)
            sums[mask] = Math.addExact(sums[mask and (mask - 1)], open[lowest].second.units)
            var best = 0
            var rest = mask
            while (rest != 0) {
                val bit = rest and -rest
                best = maxOf(best, most[mask xor bit])
                rest = rest xor bit
            }
            most[mask] = best + if (sums[mask] == 0L) 1 else 0
        }

        // Members are taken off one at a time along a path to that most, and a group closes
        // wherever what is left sums to zero.
        val groups = mutableListOf<List<Pair<MemberId, Amount>>>()
        var group = mutableListOf<Pair<MemberId, Amount>>()
        var mask = size - 1
        while (mask != 0) {
            val closes = if (sums[mask] == 0L) 1 else 0
            var rest = mask
            var bit = rest and -rest
            while (most[mask xor bit] + closes != most[mask]) {
                rest = rest xor bit
                bit = rest and -rest
            }
            group += open[Integer.numberOfTrailingZeros(bit)]
            mask = mask xor bit
            if (sums[mask] == 0L) {
                groups += group
                group = mutableListOf()
            }
        }
        return groups
    }

    /** The largest debtor pays the largest creditor until [group], summing to zero, is settled. */
    private fun settled(group: List<Pair<MemberId, Amount>>): List<Transfer> {
        val left = group.toMap().toMutableMap()
        val transfers = mutableListOf<Transfer>()
        while (left.isNotEmpty()) {
            val creditor = left.entries.minWith(BY_BALANCE)
            val debtor =
                left.entries.minWith(
                    compareBy<Map.Entry<MemberId, Amount>> { it.value }.thenBy { it.key }
                )
            val amount = minOf(creditor.value, -debtor.value)
            transfers += Transfer(debtor.key, creditor.key, amount)
            left[creditor.key] = creditor.value - amount
            left[debtor.key] = debtor.value + amount
            left.values.removeAll { it.isZero }
        }
        return transfers
    }
}
