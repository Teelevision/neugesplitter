package eu.teele.neugesplitter.domain.value

/**
 * The number of fractional digits a group's amounts are entered and displayed in, and the _d_ of
 * format-spec 7.1.
 *
 * Nothing stored changes with it — amounts are integers at 10⁻⁸ whatever it says — so it moves only
 * what is displayed and where the rounding residue falls.
 */
@JvmInline
value class Scale private constructor(val digits: Int) : Comparable<Scale> {

    /** One unit of 10⁻ᵈ, expressed in the 10⁻⁸ units of [Amount]. */
    val unit: Long
        get() = POWERS[Amount.UNIT_DIGITS - digits]

    override fun compareTo(other: Scale): Int = digits.compareTo(other.digits)

    override fun toString(): String = digits.toString()

    companion object {
        private val POWERS =
            LongArray(Amount.UNIT_DIGITS + 1).also {
                var power = 1L
                for (i in it.indices) {
                    it[i] = power
                    power *= 10
                }
            }

        /**
         * Implementations of this format version write 2 when creating a group (format-spec 4.2).
         */
        val DEFAULT: Scale = Scale(2)

        fun of(digits: Int): Scale {
            require(digits in 0..Amount.UNIT_DIGITS) { "scale must be 0 to 8, was $digits" }
            return Scale(digits)
        }
    }
}
