package eu.teele.neugesplitter.domain.calculator

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalculationTest {

    private val cents = Scale.DEFAULT

    /** Presses each key in [keys]: digits, `.`, `+ - * /`, `<` for back and `C` for clear. */
    private fun Calculation.keys(keys: String): Calculation =
        keys.fold(this) { calculation, key ->
            when (key) {
                in '0'..'9' -> calculation.digit(key - '0')
                '.' -> calculation.point()
                '+' -> calculation.operator(Operator.PLUS)
                '-' -> calculation.operator(Operator.MINUS)
                '*' -> calculation.operator(Operator.TIMES)
                '/' -> calculation.operator(Operator.DIVIDE)
                '<' -> calculation.back()
                'C' -> calculation.clear()
                else -> error("no key '$key'")
            }
        }

    private fun calculate(keys: String, scale: Scale = cents) = Calculation(scale).keys(keys)

    private fun amount(text: String) =
        Amount.ofUnits(text.toBigDecimal().movePointRight(Amount.UNIT_DIGITS).longValueExact())

    @Test
    fun `operators apply strictly left to right, without precedence (AM-2, TR-35)`() {
        assertEquals(amount("15.5"), calculate("12.5+3").value)
        assertEquals(amount("31"), calculate("12.5+3*2").value)
        assertEquals(amount("20"), calculate("2+3*4").value)
        assertEquals(amount("15"), calculate("5-10+20").value)
    }

    @Test
    fun `the result so far is there after every operator (AM-3)`() {
        val calculation = calculate("12.5+3*")
        assertEquals(amount("15.5"), calculation.result)
        assertEquals(Operator.TIMES, calculation.operator)
        assertEquals("", calculation.typed)
    }

    @Test
    fun `the last input can be taken back, and the whole calculation cleared (AM-4)`() {
        assertEquals(amount("12.4"), calculate("12.55<<4").value)
        assertEquals(amount("15"), calculate("12+<+3").value)
        assertEquals(amount("9"), calculate("12+-3").value)
        assertEquals(Calculation(cents), calculate("12+3C"))
        assertEquals(amount("7"), calculate("12+3C7").value)
    }

    @Test
    fun `an empty calculation has no value (AM-6)`() {
        assertNull(calculate("").value)
        assertNull(calculate("").fault)
    }

    @Test
    fun `an operator still waiting for its number is left out of the value`() {
        assertEquals(amount("12"), calculate("12+").value)
        assertEquals(amount("15"), calculate("12+3*").value)
        assertNull(calculate("12+").fault)
    }

    @Test
    fun `division by zero is a fault, has no value and cannot be carried on from (AM-6)`() {
        val calculation = calculate("12/0")
        assertEquals(Fault.DIVISION_BY_ZERO, calculation.fault)
        assertNull(calculation.value)
        assertEquals(calculation, calculation.operator(Operator.PLUS))
        assertEquals(amount("24"), calculation.keys(".5").value)
    }

    @Test
    fun `the result is exact to 10⁻⁸ until committed, then rounded to the scale (AM-7, TR-35)`() {
        assertEquals(amount("3.33333333"), calculate("10/3*").result)
        assertEquals(amount("3.33"), calculate("10/3").value)
        assertEquals(amount("0.67"), calculate("2/3").value)
        assertEquals(amount("10"), calculate("10/3*3").value)
        assertEquals(amount("3"), calculate("10/3", Scale.of(0)).value)
    }

    @Test
    fun `numbers are typed to the scale's decimals and to ten whole digits (AM-7)`() {
        assertEquals("12.34", calculate("12.345").typed)
        assertEquals("12", calculate("12.", Scale.of(0)).typed)
        assertEquals("9999999999", calculate("99999999999").typed)
        assertEquals("0.5", calculate(".5").typed)
        assertEquals("7", calculate("07").typed)
    }

    @Test
    fun `a result beyond what the format holds is a fault (TR-35, format-spec 3_3)`() {
        assertEquals(Fault.TOO_LARGE, calculate("9999999999*9999999999").fault)
        assertEquals(Fault.TOO_LARGE, calculate("9999999999.99*10").fault)
    }

    @Test
    fun `an amount starts out typed at the scale, ready to be changed (AM-4)`() {
        assertEquals("24.50", Calculation.start(cents, amount("24.5")).typed)
        assertEquals("25", Calculation.start(Scale.of(0), amount("24.5")).typed)
        assertEquals(amount("24.5"), Calculation.start(cents, amount("24.5")).keys("<<5").value)
        assertEquals(Calculation(cents), Calculation.start(cents, null))
    }
}
