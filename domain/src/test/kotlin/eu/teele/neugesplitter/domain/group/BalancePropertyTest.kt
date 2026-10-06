package eu.teele.neugesplitter.domain.group

import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.arbGroup
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * BA-2 and the rounding rules of format-spec 7.1, held over whole groups rather than over sides
 * alone: the generated log is replayed and the settlement taken from the resulting aggregate.
 */
class BalancePropertyTest {

    @Test
    fun `balances sum to zero once the residue is absorbed (BA-2)`() {
        runBlocking {
            checkAll(arbGroup) { group ->
                val settled = group.settlement(TODAY)
                val total = settled.values.fold(0L) { sum, value -> sum + value.units }
                assertTrue(total == 0L, "balances sum to $total at scale ${group.scale}")
            }
        }
    }

    @Test
    fun `every balance is a whole number of the group's units (BA-2)`() {
        runBlocking {
            checkAll(arbGroup) { group ->
                val settled = group.settlement(TODAY)
                assertTrue(
                    settled.values.all { it.units % group.scale.unit == 0L },
                    "not at scale ${group.scale}: $settled",
                )
            }
        }
    }

    @Test
    fun `a member in credit is never shown owing money (format-spec 7_1)`() {
        runBlocking {
            checkAll(arbGroup) { group ->
                val exact = group.balances(TODAY)
                val settled = group.settlement(TODAY)
                assertTrue(
                    settled.none { (member, value) ->
                        exact.getValue(member).isPositive && value.isNegative
                    },
                    "a creditor was pushed into debt: $exact became $settled",
                )
            }
        }
    }

    @Test
    fun `every member has a balance, whether they took part or not (BA-2)`() {
        runBlocking {
            checkAll(arbGroup) { group ->
                assertTrue(
                    group.settlement(TODAY).keys == group.members.keys,
                    "balances ${group.settlement(TODAY).keys} against members ${group.members.keys}",
                )
            }
        }
    }
}
