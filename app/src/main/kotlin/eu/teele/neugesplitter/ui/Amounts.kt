package eu.teele.neugesplitter.ui

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * The size of [amount], already at [scale], as [locale] writes numbers: always with the group's
 * decimals and never with a currency symbol (AM-1, AM-7, UX-4). Which way it goes is for the words
 * beside it to say. Display only; nothing parses this back (TR-34).
 */
fun formatted(amount: Amount, scale: Scale, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.minimumFractionDigits = scale.digits
    format.maximumFractionDigits = scale.digits
    return format.format(BigDecimal.valueOf(amount.units, Amount.UNIT_DIGITS).abs())
}
