package eu.teele.neugesplitter.domain.entry

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.value.Amount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SideTest {

    private val tenEuro = Amount.parse("1000000000")

    @Test
    fun `equal divides and keeps the remainder undistributed (SP-1)`() {
        val parts = Side.Equal(listOf(ANNA, BEN, CLARA)).parts(Amount.ofUnits(10))
        assertEquals(listOf(3L, 3L, 3L), parts.values.map { it.units })
    }

    @Test
    fun `shares divide in proportion (SP-2)`() {
        val parts = Side.Shares(mapOf(ANNA to 1L, BEN to 3L)).parts(tenEuro)
        assertEquals(Amount.parse("250000000"), parts.getValue(ANNA))
        assertEquals(Amount.parse("750000000"), parts.getValue(BEN))
    }

    @Test
    fun `shares compute the product in a wider type than 64 bits (format-spec 3_3)`() {
        val large = Amount.ofUnits(999_999_999_999_999_999L)
        val parts = Side.Shares(mapOf(ANNA to 1L, BEN to 1L)).parts(large)
        assertEquals(499_999_999_999_999_999L, parts.getValue(ANNA).units)
        assertEquals(499_999_999_999_999_999L, parts.getValue(BEN).units)
    }

    @Test
    fun `adjustments come off the top before the rest is divided (SP-3)`() {
        val parts =
            Side.Adjustments(mapOf(ANNA to Amount.parse("200000000"), BEN to Amount.ZERO))
                .parts(tenEuro)
        assertEquals(Amount.parse("600000000"), parts.getValue(ANNA))
        assertEquals(Amount.parse("400000000"), parts.getValue(BEN))
    }

    @Test
    fun `a negative adjustment is allowed (SP-3)`() {
        val parts =
            Side.Adjustments(mapOf(ANNA to Amount.parse("-200000000"), BEN to Amount.ZERO))
                .parts(tenEuro)
        assertEquals(Amount.parse("400000000"), parts.getValue(ANNA))
        assertEquals(Amount.parse("600000000"), parts.getValue(BEN))
    }

    @Test
    fun `absolute values are taken as given (SP-4)`() {
        val input = mapOf(ANNA to Amount.parse("400000000"), BEN to Amount.parse("600000000"))
        assertEquals(input, Side.Absolute(input).parts(tenEuro))
    }

    @Test
    fun `absolute values that do not sum to the amount are malformed (format-spec 7)`() {
        val side = Side.Absolute(mapOf(ANNA to Amount.parse("400000000"), BEN to Amount.ZERO))
        assertFailsWith<Malformed> { side.parts(tenEuro) }
    }

    @Test
    fun `adjustments summing to more than the amount are malformed (format-spec 7)`() {
        val side = Side.Adjustments(mapOf(ANNA to Amount.parse("1000000001"), BEN to Amount.ZERO))
        assertFailsWith<Malformed> { side.parts(tenEuro) }
    }

    @Test
    fun `a side with no members is malformed (format-spec 4_2)`() {
        assertFailsWith<Malformed> { Side.Equal(emptyList()) }
        assertFailsWith<Malformed> { Side.Absolute(emptyMap()) }
    }

    @Test
    fun `a member appearing twice is malformed (format-spec 4_2)`() {
        assertFailsWith<Malformed> { Side.Equal(listOf(ANNA, ANNA)) }
    }

    @Test
    fun `a share of zero takes nothing from the side (format-spec 7)`() {
        val parts = Side.Shares(mapOf(ANNA to 3L, BEN to 0L)).parts(tenEuro)
        assertEquals(tenEuro, parts.getValue(ANNA))
        assertEquals(Amount.ZERO, parts.getValue(BEN))
    }

    @Test
    fun `a negative share is malformed (format-spec 7)`() {
        assertFailsWith<Malformed> { Side.Shares(mapOf(ANNA to -1L, BEN to 3L)) }
    }

    @Test
    fun `shares that sum to zero cannot divide (format-spec 7)`() {
        assertFailsWith<Malformed> { Side.Shares(mapOf(ANNA to 0L, BEN to 0L)) }
    }
}
