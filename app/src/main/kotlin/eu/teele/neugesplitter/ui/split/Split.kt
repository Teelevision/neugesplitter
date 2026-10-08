package eu.teele.neugesplitter.ui.split

import eu.teele.neugesplitter.domain.entry.Scheme
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import java.math.BigDecimal

/** Why a side cannot be saved the way its split is typed (SP-6). */
enum class Mismatch {
    UNREADABLE,
    NO_SHARES,
    ABSOLUTE_OFF,
    ADJUSTMENTS_OVER,
}

/**
 * One side's split as typed: the chosen scheme, and what was typed for each member under every
 * scheme, so that switching schemes or members loses nothing (SP-1).
 *
 * A member with nothing typed holds one share, no adjustment, and an equal part of what the
 * absolute values typed for others leave of the amount.
 */
data class Split(
    val scheme: Scheme = Scheme.EQUAL,
    val shares: Map<MemberId, String> = emptyMap(),
    val adjustments: Map<MemberId, String> = emptyMap(),
    val absolute: Map<MemberId, String> = emptyMap(),
) {
    /** What [member] has typed under the current scheme. */
    fun input(member: MemberId): String =
        when (scheme) {
            Scheme.EQUAL -> ""
            Scheme.SHARES -> shares[member] ?: "1"
            Scheme.ADJUSTMENTS -> adjustments[member].orEmpty()
            Scheme.ABSOLUTE -> absolute[member].orEmpty()
        }

    /**
     * Whether [text] may replace [member]'s input: whole shares, or an amount with no more decimals
     * than [scale] — or than the input already has. Only adjustments may be negative (SP-4).
     */
    fun accepts(member: MemberId, text: String, scale: Scale): Boolean =
        when (scheme) {
            Scheme.EQUAL -> false
            Scheme.SHARES -> SHARE.matches(text)
            Scheme.ADJUSTMENTS,
            Scheme.ABSOLUTE -> {
                val match = TYPED.matchEntire(text)
                val decimals = maxOf(scale.digits, decimals(input(member)))
                match != null &&
                    (scheme == Scheme.ADJUSTMENTS || match.groupValues[1].isEmpty()) &&
                    match.groupValues[3].length <= decimals
            }
        }

    fun typed(member: MemberId, text: String): Split =
        when (scheme) {
            Scheme.EQUAL -> this
            Scheme.SHARES -> copy(shares = shares + (member to text))
            Scheme.ADJUSTMENTS -> copy(adjustments = adjustments + (member to text))
            Scheme.ABSOLUTE -> copy(absolute = absolute + (member to text))
        }

    /** The side these inputs make of [amount] for [members], or null if they make none. */
    fun side(members: List<MemberId>, amount: Amount): Side? =
        when (scheme) {
            Scheme.EQUAL -> Side.Equal(members)
            Scheme.SHARES -> {
                val input = members.associateWith { share(input(it)) ?: return null }
                if (input.values.sum() > 0) Side.Shares(input) else null
            }
            Scheme.ADJUSTMENTS -> Side.Adjustments(amounts(members) ?: return null)
            Scheme.ABSOLUTE -> Side.Absolute(absolutes(members, amount) ?: return null)
        }

    /**
     * Each member's part of [amount] as far as the inputs go, also while they do not add up yet:
     * absolute values stand as typed (SP-6). Null where nothing can be shown.
     */
    fun parts(members: List<MemberId>, amount: Amount): Map<MemberId, Amount>? =
        if (scheme == Scheme.ABSOLUTE) absolutes(members, amount)
        else side(members, amount)?.let { runCatching { it.parts(amount) }.getOrNull() }

    /**
     * How far the inputs are from adding up: for absolute values what is left of [amount] to hand
     * out, negative if they hand out more; for adjustments their sum. Null for the other schemes,
     * or while an input is not a number.
     */
    fun off(members: List<MemberId>, amount: Amount): Amount? =
        when (scheme) {
            Scheme.EQUAL,
            Scheme.SHARES -> null
            Scheme.ADJUSTMENTS -> amounts(members)?.let { Amount.sum(it.values) }
            Scheme.ABSOLUTE -> absolutes(members, amount)?.let { amount - Amount.sum(it.values) }
        }

    /** Why [members]' inputs cannot be saved with [amount], or null if they can (SP-6). */
    fun mismatch(members: List<MemberId>, amount: Amount): Mismatch? =
        when (scheme) {
            Scheme.EQUAL -> null
            Scheme.SHARES ->
                when {
                    members.any { share(input(it)) == null } -> Mismatch.UNREADABLE
                    members.sumOf { share(input(it))!! } <= 0 -> Mismatch.NO_SHARES
                    else -> null
                }
            Scheme.ADJUSTMENTS -> {
                val sum = off(members, amount)
                when {
                    sum == null -> Mismatch.UNREADABLE
                    sum > amount -> Mismatch.ADJUSTMENTS_OVER
                    else -> null
                }
            }
            Scheme.ABSOLUTE -> {
                val off = off(members, amount)
                when {
                    off == null -> Mismatch.UNREADABLE
                    off.isZero -> null
                    else -> Mismatch.ABSOLUTE_OFF
                }
            }
        }

    private fun amounts(members: List<MemberId>): Map<MemberId, Amount>? =
        members.associateWith { amount(input(it)) ?: return null }

    /**
     * Absolute values as typed, and what they leave of [total] shared out among the members left
     * blank. The units that do not divide go one each to the first of them, so that the values add
     * up exactly (format-spec 7). Typed values past [total] leave the blank ones nothing.
     */
    private fun absolutes(members: List<MemberId>, total: Amount): Map<MemberId, Amount>? {
        val blank = members.filter { input(it).isEmpty() }
        val typed = (members - blank.toSet()).associateWith { amount(input(it)) ?: return null }
        val left = (total - Amount.sum(typed.values)).units
        if (blank.isEmpty() || left < 0) return members.associateWith { typed[it] ?: Amount.ZERO }
        val each = left / blank.size
        val extra = left % blank.size
        return members.associateWith { member ->
            typed[member] ?: Amount.ofUnits(each + if (blank.indexOf(member) < extra) 1 else 0)
        }
    }

    companion object {
        private val SHARE = Regex("""\d{0,6}""")
        private val TYPED = Regex("""(-?)(\d{0,10})(?:[.,](\d*))?""")

        /** The split [side] was saved with, written out with [separator] to be edited (SP-1). */
        fun of(side: Side, scale: Scale, separator: Char): Split {
            fun text(amount: Amount) = text(amount, scale, separator)
            return when (side) {
                is Side.Equal -> Split()
                is Side.Shares ->
                    Split(Scheme.SHARES, shares = side.input.mapValues { it.value.toString() })
                is Side.Adjustments ->
                    Split(Scheme.ADJUSTMENTS, adjustments = side.input.mapValues { text(it.value) })
                is Side.Absolute ->
                    Split(Scheme.ABSOLUTE, absolute = side.input.mapValues { text(it.value) })
            }
        }

        /** [amount] as an input, with [separator] and at least [scale]'s decimals. */
        fun text(amount: Amount, scale: Scale, separator: Char): String {
            val exact = BigDecimal.valueOf(amount.units, Amount.UNIT_DIGITS).stripTrailingZeros()
            return exact
                .setScale(maxOf(scale.digits, exact.scale()))
                .toPlainString()
                .replace('.', separator)
        }

        /** Whole shares typed; nothing typed is none. */
        private fun share(text: String): Long? = if (text.isEmpty()) 0 else text.toLongOrNull()

        /** An amount typed with either separator; nothing typed is zero. */
        internal fun amount(text: String): Amount? {
            if (text.isEmpty()) return Amount.ZERO
            val match = TYPED.matchEntire(text) ?: return null
            val (sign, whole, fraction) = match.destructured
            if (whole.isEmpty() && fraction.isEmpty()) return null
            if (fraction.length > Amount.UNIT_DIGITS) return null
            val units =
                BigDecimal("${whole.ifEmpty { "0" }}.${fraction.ifEmpty { "0" }}")
                    .movePointRight(Amount.UNIT_DIGITS)
                    .longValueExact()
            return Amount.ofUnits(if (sign.isEmpty()) units else -units)
        }

        private fun decimals(text: String): Int =
            TYPED.matchEntire(text)?.groupValues?.get(3)?.length ?: 0
    }
}
