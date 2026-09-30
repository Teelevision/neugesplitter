package eu.teele.neugesplitter.domain.balance

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.MARIUS
import eu.teele.neugesplitter.domain.NORA
import eu.teele.neugesplitter.domain.OTTO
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementTest {

    private val cents = Scale.of(2)

    /** Cents as 10⁻⁸ units, so the expectations below read like money. */
    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    @Test
    fun `rounding is half away from zero (format-spec 7_1)`() {
        val balances = mapOf(ANNA to Amount.ofUnits(500_000), BEN to Amount.ofUnits(-500_000))
        assertEquals(mapOf(ANNA to cents(1), BEN to cents(-1)), Settlement.rounded(balances, cents))
    }

    @Test
    fun `the residue is taken from the largest creditor first (format-spec 7_1)`() {
        val balances =
            mapOf(
                ANNA to Amount.ofUnits(600_000),
                BEN to Amount.ofUnits(1_600_000),
                CLARA to Amount.ofUnits(2_600_000),
                MARIUS to Amount.ofUnits(-4_700_000),
            )
        // Rounded: 0.01, 0.02, 0.03, -0.05 — a residue of one cent, and Clara is owed the most.
        assertEquals(
            mapOf(ANNA to cents(1), BEN to cents(2), CLARA to cents(2), MARIUS to cents(-5)),
            Settlement.rounded(balances, cents),
        )
    }

    @Test
    fun `equal balances are ordered by member identifier ascending (format-spec 7_1)`() {
        val balances =
            mapOf(
                BEN to Amount.ofUnits(600_000),
                ANNA to Amount.ofUnits(600_000),
                CLARA to Amount.ofUnits(-1_100_000),
            )
        // Both round up to 0.01 against a debt of 0.01, so one cent goes back — Anna's, as the
        // lower identifier.
        assertEquals(
            mapOf(ANNA to Amount.ZERO, BEN to cents(1), CLARA to cents(-1)),
            Settlement.rounded(balances, cents),
        )
    }

    @Test
    fun `more units than creditors are taken round-robin (format-spec 7_1)`() {
        val balances =
            mapOf(
                ANNA to Amount.ofUnits(1_600_000),
                BEN to Amount.ofUnits(1_600_000),
                CLARA to Amount.ofUnits(-1_100_000),
                MARIUS to Amount.ofUnits(-1_100_000),
            )
        // Rounded: 0.02, 0.02, -0.01, -0.01 — a residue of 0.02 over two creditors.
        assertEquals(
            mapOf(ANNA to cents(1), BEN to cents(1), CLARA to cents(-1), MARIUS to cents(-1)),
            Settlement.rounded(balances, cents),
        )
    }

    @Test
    fun `a negative residue is given back to the creditors (format-spec 7_1)`() {
        val balances =
            mapOf(
                ANNA to Amount.ofUnits(2_200_000),
                BEN to Amount.ofUnits(-600_000),
                CLARA to Amount.ofUnits(-1_600_000),
            )
        // Rounded: 0.02, -0.01, -0.02 — a residue of -0.01, so Anna gains a cent.
        assertEquals(
            mapOf(ANNA to cents(3), BEN to cents(-1), CLARA to cents(-2)),
            Settlement.rounded(balances, cents),
        )
    }

    @Test
    fun `creditors rounded alike are ordered by the exact balance (format-spec 7_1)`() {
        val balances =
            mapOf(
                ANNA to Amount.ofUnits(1_100_000),
                BEN to Amount.ofUnits(1_400_000),
                CLARA to Amount.ofUnits(-2_500_000),
            )
        // Both round to 0.01 against a debt of 0.03, so a cent goes to Ben, who is owed more
        // exactly even though the rounded balances are equal and Anna's identifier is lower.
        assertEquals(
            mapOf(ANNA to cents(1), BEN to cents(2), CLARA to cents(-3)),
            Settlement.rounded(balances, cents),
        )
    }

    @Test
    fun `a creditor already at zero is skipped rather than pushed into debt (format-spec 7_1)`() {
        val balances =
            mapOf(
                ANNA to Amount.ofUnits(41_500_000),
                BEN to Amount.ofUnits(100_000),
                CLARA to Amount.ofUnits(-10_400_000),
                MARIUS to Amount.ofUnits(-10_400_000),
                NORA to Amount.ofUnits(-10_400_000),
                OTTO to Amount.ofUnits(-10_400_000),
            )
        // Rounded: 0.42, 0.00, -0.10 × 4 — two cents of residue. Ben is a creditor and takes his
        // turn, but he has nothing rounded to give, so both cents come from Anna.
        assertEquals(
            mapOf(
                ANNA to cents(40),
                BEN to cents(0),
                CLARA to cents(-10),
                MARIUS to cents(-10),
                NORA to cents(-10),
                OTTO to cents(-10),
            ),
            Settlement.rounded(balances, cents),
        )
    }

    @Test
    fun `with nobody in credit the residue falls on the member nearest zero (format-spec 7_1)`() {
        // One entry paid equally by three and owed 4-3-3 leaves Anna a unit down and the others
        // level: the shortfall of format-spec 7, which at scale 8 survives rounding.
        val balances = mapOf(ANNA to Amount.ofUnits(-1), BEN to Amount.ZERO, CLARA to Amount.ZERO)
        assertEquals(
            mapOf(ANNA to Amount.ofUnits(-1), BEN to Amount.ofUnits(1), CLARA to Amount.ZERO),
            Settlement.rounded(balances, Scale.of(8)),
        )
    }

    @Test
    fun `balances already at the scale are left alone (BA-2)`() {
        val balances = mapOf(ANNA to cents(500), BEN to cents(-200), CLARA to cents(-300))
        assertEquals(balances, Settlement.rounded(balances, cents))
    }
}
