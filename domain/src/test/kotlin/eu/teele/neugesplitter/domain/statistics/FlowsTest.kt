package eu.teele.neugesplitter.domain.statistics

import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.value.Amount
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FlowsTest {

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun sums(vararg amounts: Long) =
        amounts.mapIndexed { index, amount -> BuiltInCategory.entries[index].id to cents(amount) }

    @Test
    fun `a deficit is appended to income and a surplus to expenses (BA-14)`() {
        val deficit = Flows.of(sums(20), sums(50))
        assertEquals(Node.Balance(cents(30)), deficit.income.last())
        assertEquals(
            listOf(
                Node.Category(BuiltInCategory.entries[0].id, cents(20)),
                Node.Balance(cents(30)),
            ),
            deficit.income,
        )

        val surplus = Flows.of(sums(80), sums(50))
        assertEquals(Node.Balance(cents(30)), surplus.expenses.last())
        assertEquals(
            listOf(
                Node.Category(BuiltInCategory.entries[0].id, cents(50)),
                Node.Balance(cents(30)),
            ),
            surplus.expenses,
        )
    }

    @Test
    fun `equal totals need no balance node (BA-14)`() {
        val flows = Flows.of(sums(20, 30), sums(10, 40))
        assertTrue(flows.income.none { it is Node.Balance })
        assertTrue(flows.expenses.none { it is Node.Balance })
    }

    @Test
    fun `categories are largest first and balance remains last when it is largest (BA-14)`() {
        val sorted = Flows.of(sums(10, 50, 30), sums(40, 50, 0))
        assertEquals(
            listOf(
                Node.Category(BuiltInCategory.entries[1].id, cents(50)),
                Node.Category(BuiltInCategory.entries[2].id, cents(30)),
                Node.Category(BuiltInCategory.entries[0].id, cents(10)),
            ),
            sorted.income,
        )
        assertEquals(
            listOf(
                Node.Category(BuiltInCategory.entries[1].id, cents(50)),
                Node.Category(BuiltInCategory.entries[0].id, cents(40)),
                Node.Category(BuiltInCategory.entries[2].id, Amount.ZERO),
            ),
            sorted.expenses,
        )
        val largestBalance = Flows.of(sums(1), sums(100))
        assertTrue(largestBalance.income.first() is Node.Category)
        assertEquals(Node.Balance(cents(99)), largestBalance.income.last())
    }

    @Test
    fun `the limit merges smallest categories into rest before balance and preserves totals (BA-14)`() {
        val flows = Flows.of(sums(100, 40, 30, 20), sums(250), limit = 2)

        assertEquals(
            listOf(
                Node.Category(BuiltInCategory.entries[0].id, cents(100)),
                Node.Rest(cents(90)),
                Node.Balance(cents(60)),
            ),
            flows.income,
        )
        assertEquals(cents(250), Amount.sum(flows.income.map { it.amount }))
        assertEquals(cents(250), Amount.sum(flows.expenses.map { it.amount }))
    }

    @Test
    fun `empty inputs give no nodes or links (BA-14)`() {
        assertEquals(
            Flows(emptyList(), emptyList(), emptyList()),
            Flows.of(emptyList(), emptyList()),
        )
    }

    @Test
    fun `links are positive ordered and conserve each node amount (BA-14)`() {
        runBlocking {
            val amounts = Arb.list(Arb.long(1L..1_000L), 0..10)
            checkAll(amounts, amounts) { incomeAmounts, expenseAmounts ->
                val flows =
                    Flows.of(
                        incomeAmounts.mapIndexed { index, amount ->
                            BuiltInCategory.entries[index % BuiltInCategory.entries.size].id to
                                Amount.ofUnits(amount)
                        },
                        expenseAmounts.mapIndexed { index, amount ->
                            BuiltInCategory.entries[index % BuiltInCategory.entries.size].id to
                                Amount.ofUnits(amount)
                        },
                    )

                assertTrue(
                    flows.links.all { it.amount.isPositive },
                    "non-positive link: ${flows.links}",
                )
                assertTrue(
                    flows.links.zipWithNext().all { (left, right) ->
                        left.from <= right.from && left.to <= right.to
                    },
                    "crossing or unordered links: ${flows.links}",
                )
                flows.income.forEachIndexed { index, node ->
                    assertEquals(
                        node.amount,
                        Amount.sum(flows.links.filter { it.from == index }.map { it.amount }),
                    )
                }
                flows.expenses.forEachIndexed { index, node ->
                    assertEquals(
                        node.amount,
                        Amount.sum(flows.links.filter { it.to == index }.map { it.amount }),
                    )
                }
            }
        }
    }
}
