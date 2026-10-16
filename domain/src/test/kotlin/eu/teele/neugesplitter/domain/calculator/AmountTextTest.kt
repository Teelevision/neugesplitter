package eu.teele.neugesplitter.domain.calculator

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountTextTest {

    private val cents = Scale.DEFAULT

    private fun amount(text: String) =
        Amount.ofUnits(text.toBigDecimal().movePointRight(Amount.UNIT_DIGITS).longValueExact())

    @Test
    fun `a currency, a sign and spaces around are left out (AM-9)`() {
        assertEquals(amount("100"), amountIn("100€", cents))
        assertEquals(amount("1234"), amountIn("USD 1,234", cents))
        assertEquals(amount("12.5"), amountIn(" -12.50 EUR\n", cents))
        assertEquals(amount("3"), amountIn("€3", cents))
    }

    @Test
    fun `a lone separator before other than three digits is the decimal one (AM-9)`() {
        assertEquals(amount("1.99"), amountIn("1,99", cents))
        assertEquals(amount("1.99"), amountIn("1.99", cents))
        assertEquals(amount("1.5"), amountIn("1,5", cents))
        assertEquals(amount("0.5"), amountIn("0,500", cents))
        assertEquals(amount("0.12345"), amountIn("0.12345", cents))
    }

    @Test
    fun `a lone separator before three digits follows the group's decimal places (AM-9)`() {
        assertEquals(amount("1234"), amountIn("1.234", cents))
        assertEquals(amount("1234"), amountIn("1,234", Scale.of(0)))
        assertEquals(amount("1.234"), amountIn("1,234", Scale.of(3)))
    }

    @Test
    fun `of two separators the last is the decimal one, and one repeated groups (AM-9)`() {
        assertEquals(amount("1234.56"), amountIn("1.234,56", cents))
        assertEquals(amount("1234.56"), amountIn("1,234.56", cents))
        assertEquals(amount("1234567"), amountIn("1.234.567", cents))
        assertEquals(amount("1234567.8"), amountIn("1'234'567.80", cents))
        assertEquals(amount("1234.56"), amountIn("1 234,56 €", cents))
        assertNull(amountIn("1.234,56,7", cents))
    }

    @Test
    fun `text with no number, or several, holds no amount (AM-9)`() {
        assertNull(amountIn("lunch", cents))
        assertNull(amountIn("2 x 3,50", cents))
        assertNull(amountIn("12,50 2", cents))
        assertNull(amountIn("12345678901234567890", cents))
    }

    @Test
    fun `a pasted amount waits for the operator, or else stands as the result (AM-9)`() {
        val pasted = amount("2.5")
        assertEquals(Calculation.start(cents, pasted), Calculation(cents).keys("12").pasted(pasted))
        val waiting = Calculation(cents).keys("12+").pasted(amount("2.499"))
        assertEquals("2.50", waiting.typed)
        assertEquals(amount("14.5"), waiting.value)
    }

    private fun Calculation.keys(keys: String): Calculation =
        keys.fold(this) { calculation, key ->
            when (key) {
                in '0'..'9' -> calculation.digit(key - '0')
                '+' -> calculation.operator(Operator.PLUS)
                else -> error("no key '$key'")
            }
        }
}
