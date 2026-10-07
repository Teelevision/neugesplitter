package eu.teele.neugesplitter.ui

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AmountsTest {

    private fun units(text: String) = Amount.parse(text)

    @Test
    fun `amounts are written with the group's decimals as the locale writes numbers (AM-7, UX-4)`() {
        val amount = units("123450000000")
        assertEquals("1.234,50", formatted(amount, Scale.DEFAULT, Locale.GERMANY))
        assertEquals("1,234.50", formatted(amount, Scale.DEFAULT, Locale.UK))
        assertEquals("1,234.5000", formatted(amount, Scale.of(4), Locale.UK))
        assertEquals("1,235", formatted(units("123500000000"), Scale.of(0), Locale.UK))
    }

    @Test
    fun `an amount owed is written without its sign, which the words beside it carry (UX-6)`() {
        assertEquals("22,45", formatted(units("-2245000000"), Scale.DEFAULT, Locale.GERMANY))
    }
}
