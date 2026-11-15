package eu.teele.neugesplitter.domain.statistics

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId

/** One side's node of [Flows]. */
sealed interface Node {
    val amount: Amount

    /** @property id null for no category. */
    data class Category(val id: CategoryId?, override val amount: Amount) : Node

    /** The categories beyond the limit, together. */
    data class Rest(override val amount: Amount) : Node

    /** What one side lacks to match the other: a deficit on the income side, a surplus opposite. */
    data class Balance(override val amount: Amount) : Node
}

/** A band from [income]'s node [from] to [expenses]' node [to]. */
data class Link(val from: Int, val to: Int, val amount: Amount)

/**
 * Income on one side, expenses on the other, each largest first with the balance always last, and
 * the bands between them (BA-14).
 *
 * The bands show no real movement of money, which nothing records: both sides are laid along the
 * same total and each band is where two nodes overlap, so that no two bands cross.
 */
data class Flows(val income: List<Node>, val expenses: List<Node>, val links: List<Link>) {

    companion object {
        /**
         * @param income and [expenses] positive, per category, largest first.
         * @param limit how many nodes a side has at most besides the balance; the smallest
         *   categories beyond it are one [Node.Rest].
         */
        fun of(
            income: List<Pair<CategoryId?, Amount>>,
            expenses: List<Pair<CategoryId?, Amount>>,
            limit: Int = Int.MAX_VALUE,
        ): Flows {
            require(limit > 0) { "a side has room for at least one node" }
            val left = nodes(income, limit)
            val right = nodes(expenses, limit)
            val difference =
                Amount.sum(right.map { it.amount }) - Amount.sum(left.map { it.amount })
            if (difference.isPositive) left += Node.Balance(difference)
            if (difference.isNegative) right += Node.Balance(-difference)
            return Flows(left, right, links(left, right))
        }

        private fun nodes(sums: List<Pair<CategoryId?, Amount>>, limit: Int): MutableList<Node> {
            val sorted = sums.sortedByDescending { it.second }
            val shown = if (sorted.size > limit) limit - 1 else sorted.size
            val nodes =
                sorted.take(shown).mapTo(mutableListOf<Node>()) {
                    Node.Category(it.first, it.second)
                }
            if (shown < sorted.size)
                nodes += Node.Rest(Amount.sum(sorted.drop(shown).map { it.second }))
            return nodes
        }

        private fun links(left: List<Node>, right: List<Node>): List<Link> {
            val links = mutableListOf<Link>()
            var i = 0
            var j = 0
            var leftOver = left.firstOrNull()?.amount ?: Amount.ZERO
            var rightOver = right.firstOrNull()?.amount ?: Amount.ZERO
            while (i < left.size && j < right.size) {
                val amount = minOf(leftOver, rightOver)
                if (amount.isPositive) links += Link(i, j, amount)
                leftOver -= amount
                rightOver -= amount
                if (leftOver.isZero && ++i < left.size) leftOver = left[i].amount
                if (rightOver.isZero && ++j < right.size) rightOver = right[j].amount
            }
            return links
        }
    }
}
