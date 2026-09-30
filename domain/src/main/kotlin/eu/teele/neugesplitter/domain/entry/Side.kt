package eu.teele.neugesplitter.domain.entry

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import java.math.BigInteger

/** The four ways an entry's amount is divided across the members of one side (format-spec 4.2). */
enum class Scheme {
    EQUAL,
    SHARES,
    ADJUSTMENTS,
    ABSOLUTE,
}

/**
 * An event that cannot be split is malformed, and a malformed event stops reading rather than being
 * skipped (format-spec 8): an ignored event is a balance that is wrong on one device and right on
 * every other, silently and permanently.
 */
class MalformedSide(message: String) : IllegalArgumentException(message)

/**
 * One side of an entry — its payers or its beneficiaries (format-spec 4.2).
 *
 * Only the inputs are stored. The parts are derived on every read, so a later version may compute
 * them more precisely without any stored data becoming wrong (event-model 3).
 */
sealed interface Side {

    val scheme: Scheme

    /** The members on this side, in the order the event lists them. */
    val members: List<MemberId>

    /**
     * Each member's part of [amount], by the rule for this scheme in format-spec 7.
     *
     * No remainder is distributed, so the parts may fall short of [amount] by up to one unit of
     * 10⁻⁸ per member. That shortfall is what format-spec 7.1 absorbs at settlement.
     *
     * @throws MalformedSide if the inputs cannot be reconciled with [amount].
     */
    fun parts(amount: Amount): Map<MemberId, Amount>

    data class Equal(override val members: List<MemberId>) : Side {
        override val scheme = Scheme.EQUAL

        init {
            requireNonEmptyAndDistinct(members)
        }

        override fun parts(amount: Amount): Map<MemberId, Amount> {
            val each = Amount.ofUnits(amount.units / members.size)
            return members.associateWith { each }
        }
    }

    data class Shares(val input: Map<MemberId, Long>) : Side {
        override val scheme = Scheme.SHARES
        override val members: List<MemberId>
            get() = input.keys.toList()

        private val total: Long = input.values.sum()

        init {
            requireNonEmptyAndDistinct(input.keys.toList())
            if (input.values.any { it < 0 }) throw MalformedSide("a share is negative: $input")
            if (total <= 0) throw MalformedSide("shares sum to $total, which cannot divide")
        }

        override fun parts(amount: Amount): Map<MemberId, Amount> {
            val units = BigInteger.valueOf(amount.units)
            val divisor = BigInteger.valueOf(total)
            return input.mapValues { (_, share) ->
                Amount.ofUnits(units.multiply(BigInteger.valueOf(share)).divide(divisor).toLong())
            }
        }
    }

    data class Adjustments(val input: Map<MemberId, Amount>) : Side {
        override val scheme = Scheme.ADJUSTMENTS
        override val members: List<MemberId>
            get() = input.keys.toList()

        init {
            requireNonEmptyAndDistinct(input.keys.toList())
        }

        override fun parts(amount: Amount): Map<MemberId, Amount> {
            val adjusted = Amount.sum(input.values)
            if (adjusted > amount) {
                throw MalformedSide("adjustments sum to $adjusted, more than the amount $amount")
            }
            val base = (amount - adjusted).units / input.size
            return input.mapValues { (_, adjustment) -> Amount.ofUnits(base) + adjustment }
        }
    }

    data class Absolute(val input: Map<MemberId, Amount>) : Side {
        override val scheme = Scheme.ABSOLUTE
        override val members: List<MemberId>
            get() = input.keys.toList()

        init {
            requireNonEmptyAndDistinct(input.keys.toList())
        }

        override fun parts(amount: Amount): Map<MemberId, Amount> {
            val stated = Amount.sum(input.values)
            if (stated != amount) {
                throw MalformedSide("absolute values sum to $stated, not the amount $amount")
            }
            return input
        }
    }

    private companion object {
        fun requireNonEmptyAndDistinct(members: List<MemberId>) {
            if (members.isEmpty()) throw MalformedSide("a side has no members")
            if (members.toSet().size != members.size) {
                throw MalformedSide("a member appears twice on one side: $members")
            }
        }
    }
}
