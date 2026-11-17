package eu.teele.neugesplitter.domain.entry

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsIncomeTest {

    private val anna = MemberId.parse("01".repeat(16))
    private val ben = MemberId.parse("02".repeat(16))
    private val cleo = MemberId.parse("03".repeat(16))

    private fun units(euros: Long) = Amount.ofUnits(euros * 100_000_000)

    private fun expense(amount: Amount, payers: Side, beneficiaries: Side) =
        Entry(
            EntryId.parse("0a".repeat(16)),
            Kind.EXPENSE,
            "Gehalt",
            EntryDate.parse("2026-10-01"),
            amount,
            payers,
            beneficiaries,
        )

    private fun assertSameBalances(before: Entry, after: Entry) {
        val was = before.contributions()
        val now = after.contributions()
        for (member in was.keys + now.keys) {
            val difference = (was[member] ?: Amount.ZERO).units - (now[member] ?: Amount.ZERO).units
            assertTrue(abs(difference) <= 3, "$member moved by $difference")
        }
    }

    @Test
    fun `an equal expense paid by the other becomes an equal income of the one owing`() {
        val before = expense(units(100), Side.Equal(listOf(ben)), Side.Equal(listOf(anna, ben)))

        val after = before.asIncome()!!

        assertEquals(Kind.INCOME, after.kind)
        assertEquals(units(100), after.amount)
        assertEquals(Side.Equal(listOf(anna)), after.payers)
        assertEquals(Side.Equal(listOf(anna, ben)), after.beneficiaries)
        assertEquals(before.contributions(), after.contributions())
    }

    @Test
    fun `money owed in full becomes an income that belongs to the payer`() {
        val before = expense(units(100), Side.Equal(listOf(ben)), Side.Equal(listOf(anna)))

        val after = before.asIncome()!!

        assertEquals(Side.Equal(listOf(anna)), after.payers)
        assertEquals(Side.Equal(listOf(ben)), after.beneficiaries)
        assertEquals(before.contributions(), after.contributions())
    }

    @Test
    fun `an uneven split becomes absolute parts`() {
        val before =
            expense(
                units(90),
                Side.Absolute(mapOf(ben to units(70), cleo to units(20))),
                Side.Shares(mapOf(anna to 2, ben to 1)),
            )

        val after = before.asIncome()!!

        assertEquals(Side.Equal(listOf(anna)), after.payers)
        assertEquals(
            Side.Absolute(mapOf(anna to units(30), ben to units(40), cleo to units(20))),
            after.beneficiaries,
        )
        assertEquals(before.contributions(), after.contributions())
    }

    @Test
    fun `a split that does not divide evenly keeps the balances within the truncation`() {
        val before =
            expense(units(100), Side.Equal(listOf(ben)), Side.Equal(listOf(anna, ben, cleo)))

        assertNull(before.asIncome(), "two members owe, so no single receiver")

        val owedByOne =
            expense(units(100), Side.Equal(listOf(ben, cleo)), Side.Equal(listOf(anna, ben, cleo)))
        val after = owedByOne.asIncome()!!
        assertEquals(listOf(anna, ben, cleo), after.beneficiaries.members)
        assertSameBalances(owedByOne, after)
    }

    @Test
    fun `nothing to convert when nobody owes or the entry is not an expense`() {
        val own = expense(units(10), Side.Equal(listOf(anna)), Side.Equal(listOf(anna)))
        assertNull(own.asIncome())
        val income =
            expense(units(10), Side.Equal(listOf(anna)), Side.Equal(listOf(ben)))
                .copy(kind = Kind.INCOME)
        assertNull(income.asIncome())
    }

    @Test
    fun `income words match in English and German`() {
        listOf(
                "Gehalt Oktober",
                "Monatsgehalt",
                "sold the old sofa",
                "Rente März",
                "Kindergeld",
                "eBay Verkauf",
                "von Oma",
                "Von Papa für den Urlaub",
                "from Grandma",
            )
            .forEach { assertTrue(IncomeWords.match(it), it) }
        listOf(
                "Groceries",
                "Rentenversicherung",
                "Lohnsteuer",
                "Pizza",
                "Steuererstattung",
                "Pfand zurück",
                "Kaution",
                "Tax refund",
                "Cashback",
                "Von",
                "Brötchen von Bäcker",
            )
            .forEach { assertFalse(IncomeWords.match(it), it) }
    }
}
