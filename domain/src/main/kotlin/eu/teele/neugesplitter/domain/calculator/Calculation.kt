package eu.teele.neugesplitter.domain.calculator

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

enum class Operator {
    PLUS,
    MINUS,
    TIMES,
    DIVIDE,
}

/** Why a calculation has no value (AM-6). An unfinished one simply has none yet. */
enum class Fault {
    DIVISION_BY_ZERO,
    TOO_LARGE,
}

/**
 * A pocket calculator: each operator commits what came before it, strictly left to right, with no
 * precedence and no parentheses (AM-2, TR-35). Every key returns the next calculation.
 *
 * @property result what the calculation came to when the last operator was pressed (AM-3), exact to
 *   10⁻⁸; null before the first.
 * @property operator the operator waiting for its right-hand number.
 * @property typed the number being typed, in the invariant grammar (TR-34): digits, and at most
 *   [scale] of them after a point (AM-7).
 */
data class Calculation(
    val scale: Scale,
    val result: Amount? = null,
    val operator: Operator? = null,
    val typed: String = "",
) {
    init {
        val match = TYPED.matchEntire(typed)
        require(match != null) { "not a number being typed: '$typed'" }
        require(match.groupValues[2].length <= scale.digits) { "too many decimals: '$typed'" }
        require(scale.digits > 0 || '.' !in typed) { "no decimals at scale 0: '$typed'" }
        require(operator == null || result != null) { "an operator needs a result before it" }
        require(result == null || operator != null || typed.isEmpty()) {
            "a number typed after a result needs an operator between them"
        }
    }

    private val outcome: Outcome by lazy { outcome() }

    /**
     * What confirming gives, rounded to [scale] (AM-7, TR-35), or null if the calculation is empty
     * or meaningless (AM-6). An operator still waiting for its number is left out.
     */
    val value: Amount?
        get() = (outcome as? Outcome.Value)?.amount

    val fault: Fault?
        get() = (outcome as? Outcome.Failed)?.fault

    fun digit(digit: Int): Calculation {
        require(digit in 0..9) { "not a digit: $digit" }
        val from = fresh()
        val text = if (from.typed == "0") "$digit" else from.typed + digit
        return if (fits(text)) from.copy(typed = text) else this
    }

    fun point(): Calculation {
        val from = fresh()
        if (scale.digits == 0 || '.' in from.typed) return this
        return from.copy(typed = from.typed.ifEmpty { "0" } + ".")
    }

    /**
     * Commits the calculation so far and waits for the number [operator] takes. Pressed again
     * before that number, it replaces the operator instead (AM-4). A calculation that has failed
     * stays as it is, so the fault can be corrected (AM-6).
     */
    fun operator(operator: Operator): Calculation {
        if (typed.isEmpty()) return if (result == null) this else copy(operator = operator)
        val committed = combined() as? Outcome.Value ?: return this
        return Calculation(scale, committed.amount, operator)
    }

    /** Takes back the last digit or point, or else the operator waiting for its number (AM-4). */
    fun back(): Calculation =
        when {
            typed.isNotEmpty() -> copy(typed = typed.dropLast(1))
            operator != null -> copy(operator = null)
            else -> this
        }

    fun clear(): Calculation = Calculation(scale)

    /** A digit or point after a result with no operator starts a new calculation. */
    private fun fresh(): Calculation = if (result != null && operator == null) clear() else this

    private fun fits(text: String): Boolean {
        val match = TYPED.matchEntire(text) ?: return false
        return match.groupValues[1].length <= WHOLE_DIGITS &&
            match.groupValues[2].length <= scale.digits
    }

    private fun outcome(): Outcome {
        if (typed.isEmpty() && result == null) return Outcome.Unfinished
        val exact = combined()
        if (exact !is Outcome.Value) return exact
        return within(BigInteger.valueOf(exact.amount.roundedTo(scale).units))
    }

    /** [result], [operator] and [typed] taken together, exact to 10⁻⁸. */
    private fun combined(): Outcome {
        val left = result
        if (typed.isEmpty()) return Outcome.Value(left!!)
        val right = BigDecimal(typed).movePointRight(Amount.UNIT_DIGITS).toBigIntegerExact()
        if (left == null || operator == null) return within(right)
        val l = BigInteger.valueOf(left.units)
        return when (operator) {
            Operator.PLUS -> within(l + right)
            Operator.MINUS -> within(l - right)
            Operator.TIMES -> within(rounded(l * right, ONE))
            Operator.DIVIDE ->
                if (right.signum() == 0) Outcome.Failed(Fault.DIVISION_BY_ZERO)
                else within(rounded(l * ONE, right))
        }
    }

    private fun within(units: BigInteger): Outcome =
        if (units.abs() < LIMIT) Outcome.Value(Amount.ofUnits(units.toLong()))
        else Outcome.Failed(Fault.TOO_LARGE)

    private sealed interface Outcome {
        data class Value(val amount: Amount) : Outcome

        data class Failed(val fault: Fault) : Outcome

        data object Unfinished : Outcome
    }

    companion object {
        /** Ten whole digits keep a typed number below the format's 10¹⁸ units (format-spec 3.3). */
        private const val WHOLE_DIGITS = 18 - Amount.UNIT_DIGITS

        private val TYPED = Regex("""(?:(0|[1-9][0-9]*)(?:\.([0-9]*))?)?""")

        private val ONE = BigInteger.TEN.pow(Amount.UNIT_DIGITS)

        private val LIMIT = BigInteger.valueOf(Amount.LIMIT)

        /** [amount], ready to be changed, typed out at [scale]; empty unless it is positive. */
        fun start(scale: Scale, amount: Amount? = null): Calculation {
            if (amount == null || !amount.isPositive) return Calculation(scale)
            val text =
                BigDecimal.valueOf(amount.units, Amount.UNIT_DIGITS)
                    .setScale(scale.digits, RoundingMode.HALF_UP)
                    .toPlainString()
            return Calculation(scale, typed = text)
        }

        /** [dividend] / [divisor] to the nearest integer, half away from zero (format-spec 7.1). */
        private fun rounded(dividend: BigInteger, divisor: BigInteger): BigInteger =
            BigDecimal(dividend).divide(BigDecimal(divisor), 0, RoundingMode.HALF_UP).toBigInteger()
    }
}
