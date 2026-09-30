package eu.teele.neugesplitter.domain.value

/**
 * An integer count of 10⁻⁸ units (format-spec 3.3).
 *
 * The unit is not a cent and the app has no currency (AM-1); 10⁻⁸ is only ever the arithmetic, and
 * [Scale] is what a user types and reads. No bare `Long` amount crosses a function signature, so
 * that the unit cannot be mistaken.
 */
@JvmInline
value class Amount private constructor(val units: Long) : Comparable<Amount> {

    operator fun plus(other: Amount): Amount = Amount(Math.addExact(units, other.units))

    operator fun minus(other: Amount): Amount = Amount(Math.subtractExact(units, other.units))

    operator fun unaryMinus(): Amount = Amount(Math.negateExact(units))

    override fun compareTo(other: Amount): Int = units.compareTo(other.units)

    val isZero: Boolean
        get() = units == 0L

    val isPositive: Boolean
        get() = units > 0L

    val isNegative: Boolean
        get() = units < 0L

    /**
     * Rounded to [scale], half away from zero (format-spec 7.1 step 1). The result is still in 10⁻⁸
     * units, but is a multiple of [Scale.unit].
     */
    fun roundedTo(scale: Scale): Amount {
        val unit = scale.unit
        if (unit == 1L) return this
        val half = unit / 2
        val magnitude = Math.abs(units)
        val rounded = (magnitude + half) / unit * unit
        return Amount(if (units < 0) -rounded else rounded)
    }

    /** The canonical wire form: a decimal integer, no separator, no exponent (format-spec 3.3). */
    override fun toString(): String = units.toString()

    companion object {
        /** The number of fractional digits the stored unit represents. */
        const val UNIT_DIGITS: Int = 8

        /** Absolute values are less than 10¹⁸ (format-spec 3.3). */
        const val LIMIT: Long = 1_000_000_000_000_000_000L

        val ZERO: Amount = Amount(0L)

        private val WIRE = Regex("""-?(0|[1-9][0-9]*)""")

        fun ofUnits(units: Long): Amount {
            require(units > -LIMIT && units < LIMIT) { "amount out of range: $units" }
            return Amount(units)
        }

        /** Parses the wire form of format-spec 3.3, which is stricter than [String.toLong]. */
        fun parse(text: String): Amount {
            require(WIRE.matches(text)) { "not a canonical amount: '$text'" }
            val units =
                text.toLongOrNull()
                    ?: throw IllegalArgumentException("amount out of range: '$text'")
            return ofUnits(units)
        }

        /** Sums without the per-amount range check, which bounds a single value and not a total. */
        fun sum(amounts: Iterable<Amount>): Amount =
            amounts.fold(ZERO) { acc, amount -> acc + amount }
    }
}
