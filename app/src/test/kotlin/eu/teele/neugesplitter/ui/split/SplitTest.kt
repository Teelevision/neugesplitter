package eu.teele.neugesplitter.ui.split

import eu.teele.neugesplitter.domain.entry.Scheme
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitTest {

    private val anna = MemberId.parse("a1".repeat(16))
    private val ben = MemberId.parse("b2".repeat(16))
    private val both = listOf(anna, ben)
    private val total = money("30")

    private fun money(text: String) = Split.amount(text)!!

    @Test
    fun `a side starts out split equally (SP-2)`() {
        val split = Split()
        assertEquals(Side.Equal(both), split.side(both, total))
        assertEquals(mapOf(anna to money("15"), ben to money("15")), split.parts(both, total))
        assertNull(split.mismatch(both, total))
    }

    @Test
    fun `shares divide the amount in proportion, one each until typed (SP-3)`() {
        val split = Split(Scheme.SHARES).typed(anna, "2")
        assertEquals(Side.Shares(mapOf(anna to 2L, ben to 1L)), split.side(both, total))
        assertEquals(mapOf(anna to money("20"), ben to money("10")), split.parts(both, total))
        assertNull(split.mismatch(both, total))
    }

    @Test
    fun `shares that add up to nothing cannot be saved (SP-3, SP-6)`() {
        val split = Split(Scheme.SHARES).typed(anna, "0").typed(ben, "")
        assertNull(split.side(both, total))
        assertEquals(Mismatch.NO_SHARES, split.mismatch(both, total))
    }

    @Test
    fun `adjustments shift an equal split, each on top of its share of the rest (SP-4)`() {
        val split = Split(Scheme.ADJUSTMENTS).typed(anna, "5").typed(ben, "-5")
        assertEquals(mapOf(anna to money("20"), ben to money("10")), split.parts(both, total))
        assertNull(split.mismatch(both, total))

        val uneven = split.typed(anna, "1").typed(ben, "-0,50")
        assertEquals(
            mapOf(anna to money("15.75"), ben to money("14.25")),
            uneven.parts(both, total),
        )
        assertNull(uneven.mismatch(both, total))
    }

    @Test
    fun `adjustments cannot add up to more than the amount (SP-6)`() {
        val split = Split(Scheme.ADJUSTMENTS).typed(anna, "30")
        assertNull(split.mismatch(both, total))
        assertEquals(Mismatch.ADJUSTMENTS_OVER, split.typed(ben, "0.01").mismatch(both, total))
    }

    @Test
    fun `absolute values stand as typed and must add up to the total (SP-5, SP-6)`() {
        val split = Split(Scheme.ABSOLUTE).typed(anna, "12.5").typed(ben, "10")
        assertEquals(mapOf(anna to money("12.5"), ben to money("10")), split.parts(both, total))
        assertEquals(money("7.5"), split.off(both, total))
        assertEquals(Mismatch.ABSOLUTE_OFF, split.mismatch(both, total))

        val over = split.typed(ben, "20")
        assertEquals(-money("2.5"), over.off(both, total))

        val exact = split.typed(ben, "17,50")
        assertNull(exact.mismatch(both, total))
        assertEquals(
            Side.Absolute(mapOf(anna to money("12.5"), ben to money("17.5"))),
            exact.side(both, total),
        )
    }

    @Test
    fun `members left blank share what the absolute values leave, adding up exactly (SP-5)`() {
        val carl = MemberId.parse("c3".repeat(16))
        val all = listOf(anna, ben, carl)
        val split = Split(Scheme.ABSOLUTE).typed(anna, "20")
        assertEquals(
            mapOf(anna to money("20"), ben to money("5"), carl to money("5")),
            split.parts(all, total),
        )
        assertNull(split.mismatch(all, total))

        val thirds = Split(Scheme.ABSOLUTE).parts(all, money("10"))
        assertEquals(
            listOf(333_333_334L, 333_333_333L, 333_333_333L),
            all.map { thirds!!.getValue(it).units },
        )

        val over = split.typed(anna, "40")
        assertEquals(Amount.ZERO, over.parts(all, total)!!.getValue(ben))
        assertEquals(Mismatch.ABSOLUTE_OFF, over.mismatch(all, total))
    }

    @Test
    fun `an input that is not a number yet cannot be saved (SP-6)`() {
        val split = Split(Scheme.ADJUSTMENTS).typed(anna, "-")
        assertNull(split.side(both, total))
        assertEquals(Mismatch.UNREADABLE, split.mismatch(both, total))
    }

    @Test
    fun `what was typed under one scheme survives choosing another (SP-1)`() {
        val split = Split(Scheme.SHARES).typed(anna, "3").copy(scheme = Scheme.ABSOLUTE)
        assertEquals("", split.input(anna))
        assertEquals("3", split.copy(scheme = Scheme.SHARES).input(anna))
    }

    @Test
    fun `inputs take whole shares and amounts to the group's decimals, negative only as adjustments`() {
        val scale = Scale.DEFAULT
        val shares = Split(Scheme.SHARES)
        assertTrue(shares.accepts(anna, "12", scale))
        assertFalse(shares.accepts(anna, "1.5", scale))
        val absolute = Split(Scheme.ABSOLUTE)
        assertTrue(absolute.accepts(anna, "3,25", scale))
        assertFalse(absolute.accepts(anna, "3,255", scale))
        assertFalse(absolute.accepts(anna, "-3", scale))
        assertTrue(Split(Scheme.ADJUSTMENTS).accepts(anna, "-3.2", scale))
    }

    @Test
    fun `a saved split is written out to be edited as it was (SP-1)`() {
        val side = Side.Absolute(mapOf(anna to money("12.5"), ben to money("17.123")))
        val split = Split.of(side, Scale.DEFAULT, ',')
        assertEquals(Scheme.ABSOLUTE, split.scheme)
        assertEquals("12,50", split.input(anna))
        assertEquals("17,123", split.input(ben))
        assertEquals(side, split.side(both, money("29.623")))
        assertTrue(split.accepts(ben, "17,12", Scale.DEFAULT))
    }
}
