package eu.teele.neugesplitter.domain.calculator

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import java.math.BigDecimal
import java.math.RoundingMode

/** Digits, with `.` or `,` between them, or a space or apostrophe before a group of three. */
private val NUMBER = Regex("""[0-9]+(?:(?:[.,]|[ '’\u00A0\u202F](?=[0-9]{3}(?![0-9])))[0-9]+)*""")

private val GROUPING = setOf(' ', '\'', '’', '\u00A0', '\u202F')

/**
 * The one amount written in [text], as copied from elsewhere: "100€", "1,99", "USD 1,234" (AM-9). A
 * currency and a sign are left out. Of `.` and `,` together the last is the decimal separator, and
 * one repeated groups digits. A lone one before three digits groups them unless [scale] has three
 * decimal places; otherwise it is the decimal separator. Exact to 10⁻⁸, not yet rounded to [scale];
 * null if [text] holds no number, or several.
 */
fun amountIn(text: String, scale: Scale): Amount? {
    val number = NUMBER.findAll(text).singleOrNull()?.value ?: return null
    val written = number.filterNot { it in GROUPING }
    val separators = written.filter { it == '.' || it == ',' }
    val plain =
        when {
            separators.isEmpty() -> written
            separators.toSet().size == 2 -> {
                val point = separators.last()
                if (separators.count { it == point } > 1) return null
                written.filter { it.isDigit() || it == point }.replace(point, '.')
            }
            separators.length > 1 -> written.filter { it.isDigit() }
            else -> {
                val (whole, fraction) = written.split(separators.single())
                if (fraction.length == 3 && scale.digits != 3 && whole != "0") whole + fraction
                else "$whole.$fraction"
            }
        }
    val units =
        BigDecimal(plain)
            .movePointRight(Amount.UNIT_DIGITS)
            .setScale(0, RoundingMode.HALF_UP)
            .toBigInteger()
    return if (units < Amount.LIMIT.toBigInteger()) Amount.ofUnits(units.toLong()) else null
}
