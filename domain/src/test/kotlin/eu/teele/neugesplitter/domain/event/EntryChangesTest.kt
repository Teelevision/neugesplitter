package eu.teele.neugesplitter.domain.event

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.value.Amount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EntryChangesTest {

    private val before =
        entry(
            1,
            Amount.parse("3000000000"),
            Side.Equal(listOf(ANNA)),
            Side.Equal(listOf(ANNA, BEN)),
        )

    @Test
    fun `an update names only the fields that changed (format-spec 4_2)`() {
        val after =
            before.copy(description = "Dinner", beneficiaries = Side.Equal(listOf(BEN, CLARA)))
        assertEquals(
            EntryChanges(
                description = Change.To("Dinner"),
                beneficiaries = Change.To(Side.Equal(listOf(BEN, CLARA))),
            ),
            EntryChanges.between(before, after),
        )
    }

    @Test
    fun `an entry left as it was changes nothing (EN-9)`() {
        assertTrue(EntryChanges.between(before, before.copy()).isEmpty)
    }

    @Test
    fun `changes are only taken between versions of one entry (EN-9)`() {
        val other =
            entry(2, before.amount, before.payers, before.beneficiaries, description = "Entry 1")
        assertFailsWith<IllegalArgumentException> { EntryChanges.between(before, other) }
    }
}
