package eu.teele.neugesplitter.domain.balance

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.MARIUS
import eu.teele.neugesplitter.domain.NORA
import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.arbGroup
import eu.teele.neugesplitter.domain.member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TransferTest {

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    /** What is left of [balances] once [transfers] are made. */
    private fun after(balances: Map<MemberId, Amount>, transfers: List<Transfer>) =
        transfers.fold(balances) { left, transfer ->
            left +
                mapOf(
                    transfer.from to left.getValue(transfer.from) + transfer.amount,
                    transfer.to to left.getValue(transfer.to) - transfer.amount,
                )
        }

    @Test
    fun `a settled group needs no transfers (BA-3)`() {
        assertEquals(
            emptyList(),
            Settlement.transfers(mapOf(ANNA to Amount.ZERO, BEN to Amount.ZERO)),
        )
    }

    @Test
    fun `the debtor pays the creditor (BA-3)`() {
        assertEquals(
            listOf(Transfer(BEN, ANNA, cents(1230))),
            Settlement.transfers(mapOf(ANNA to cents(1230), BEN to cents(-1230))),
        )
    }

    @Test
    fun `balances that cancel out are settled between themselves (BA-4)`() {
        // Largest debtor to largest creditor would take four transfers: Clara pays Anna 7, Marius
        // pays Ben 6, and Nora's 4 is split between the two.
        val balances =
            mapOf(
                ANNA to cents(1000),
                BEN to cents(700),
                CLARA to cents(-700),
                MARIUS to cents(-600),
                NORA to cents(-400),
            )
        assertEquals(
            listOf(
                Transfer(CLARA, BEN, cents(700)),
                Transfer(MARIUS, ANNA, cents(600)),
                Transfer(NORA, ANNA, cents(400)),
            ),
            Settlement.transfers(balances),
        )
    }

    @Test
    fun `ties go to the lower member identifier (BA-4)`() {
        val balances = mapOf(BEN to cents(100), ANNA to cents(100), CLARA to cents(-200))
        assertEquals(
            listOf(Transfer(CLARA, ANNA, cents(100)), Transfer(CLARA, BEN, cents(100))),
            Settlement.transfers(balances),
        )
    }

    @Test
    fun `beyond the exact limit every balance is still settled (BA-3)`() {
        val count = Settlement.EXACT + 4
        val balances =
            (1..count).associate { n ->
                member(n) to cents(if (n == count) -(count - 1) * (count - 1L) else n.toLong())
            } + (member(count + 1) to cents((count - 1L) * (count - 1L) - (count - 1L) * count / 2))
        val transfers = Settlement.transfers(balances)
        assertTrue(after(balances, transfers).values.all { it.isZero })
        assertTrue(transfers.size < balances.size)
    }

    @Test
    fun `balances must sum to zero`() {
        assertFailsWith<IllegalArgumentException> { Settlement.transfers(mapOf(ANNA to cents(1))) }
    }

    @Test
    fun `the transfers settle every group, one fewer than members with a balance at most (BA-3, BA-4)`() {
        runBlocking {
            checkAll(arbGroup) { group ->
                val balances = group.settlement(TODAY)
                val transfers = Settlement.transfers(balances)
                assertTrue(after(balances, transfers).values.all { it.isZero }, "$transfers")
                assertTrue(transfers.all { it.amount.isPositive && it.from != it.to })
                val open = balances.values.count { !it.isZero }
                assertTrue(transfers.size <= maxOf(open - 1, 0), "$balances: $transfers")
            }
        }
    }
}
