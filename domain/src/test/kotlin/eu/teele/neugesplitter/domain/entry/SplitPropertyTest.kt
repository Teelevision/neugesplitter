package eu.teele.neugesplitter.domain.entry

import eu.teele.neugesplitter.domain.balance.Settlement
import eu.teele.neugesplitter.domain.member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The properties TR-46 asks for: the truncation bound of format-spec 7, and BA-2. */
class SplitPropertyTest {

    @Test
    fun `parts never exceed the amount and fall short by less than one unit per member (SP-7)`() {
        runBlocking {
            checkAll(arbSplit) { (amount, side) ->
                val parts = side.parts(amount)
                val shortfall = amount.units - parts.values.sumOf { it.units }
                assertTrue(
                    shortfall >= 0 && shortfall < parts.size,
                    "shortfall $shortfall outside [0, ${parts.size}) for $side of $amount",
                )
            }
        }
    }

    @Test
    fun `balances sum to zero after the residue is absorbed (BA-2)`() {
        runBlocking {
            checkAll(Arb.list(arbEntry, 1..8), arbScale) { entries, scale ->
                val settled = Settlement.rounded(balances(entries), scale)
                assertTrue(
                    Amount.sum(settled.values).isZero,
                    "balances sum to ${Amount.sum(settled.values)} at scale $scale",
                )
            }
        }
    }

    @Test
    fun `every settled balance is a whole number of the group's units (BA-2)`() {
        runBlocking {
            checkAll(Arb.list(arbEntry, 1..8), arbScale) { entries, scale ->
                val settled = Settlement.rounded(balances(entries), scale)
                assertTrue(
                    settled.values.all { it.units % scale.unit == 0L },
                    "not at scale: $settled",
                )
            }
        }
    }

    @Test
    fun `a member in credit is never shown owing money (format-spec 7_1)`() {
        runBlocking {
            checkAll(Arb.list(arbEntry, 1..8), arbScale) { entries, scale ->
                val exact = balances(entries)
                val settled = Settlement.rounded(exact, scale)
                assertTrue(
                    settled.none { (member, value) ->
                        exact.getValue(member).isPositive && value.isNegative
                    },
                    "a creditor was pushed into debt: $exact became $settled at scale $scale",
                )
            }
        }
    }
}

private data class Split(val amount: Amount, val side: Side)

private data class GeneratedEntry(val amount: Amount, val payers: Side, val beneficiaries: Side)

/** What each member paid, less what they owed, exactly and at 10⁻⁸ (format-spec 7.1). */
private fun balances(entries: List<GeneratedEntry>): Map<MemberId, Amount> {
    val totals = mutableMapOf<MemberId, Amount>()
    for (entry in entries) {
        entry.payers.parts(entry.amount).forEach { (member, paid) ->
            totals[member] = (totals[member] ?: Amount.ZERO) + paid
        }
        entry.beneficiaries.parts(entry.amount).forEach { (member, owed) ->
            totals[member] = (totals[member] ?: Amount.ZERO) - owed
        }
    }
    return totals
}

private val arbScale: Arb<Scale> = Arb.int(0..8).map { Scale.of(it) }

private val arbAmount: Arb<Amount> = Arb.long(1L..100_000_000_000L).map { Amount.ofUnits(it) }

/** A side valid for [amount]: `absolute` and `adjustments` are only ever valid for one amount. */
private fun arbSideFor(amount: Amount): Arb<Side> = arbitrary {
    val members = (1..Arb.int(1..5).bind()).map { member(it) }
    val n = members.size
    when (Arb.int(0..3).bind()) {
        0 -> Side.Equal(members)
        1 -> {
            // Shares are natural numbers and only their sum has to be positive.
            val shares = members.associateWith { Arb.long(0L..1000L).bind() }
            Side.Shares(if (shares.values.sum() > 0) shares else shares + (members[0] to 1L))
        }
        2 -> {
            // Each adjustment within ±(amount / n) keeps their sum inside the amount.
            val bound = amount.units / n
            Side.Adjustments(
                members.associateWith { Amount.ofUnits(Arb.long(-bound..bound).bind()) }
            )
        }
        else -> {
            // Cut points in [0, amount] give parts that are non-negative and sum exactly.
            val cuts = (1 until n).map { Arb.long(0L..amount.units).bind() }.sorted() + amount.units
            var previous = 0L
            val values =
                cuts.map { cut ->
                    val part = cut - previous
                    previous = cut
                    Amount.ofUnits(part)
                }
            Side.Absolute(members.zip(values).toMap())
        }
    }
}

private val arbSplit: Arb<Split> = arbitrary {
    val amount = arbAmount.bind()
    Split(amount, arbSideFor(amount).bind())
}

private val arbEntry: Arb<GeneratedEntry> = arbitrary {
    val amount = arbAmount.bind()
    GeneratedEntry(amount, arbSideFor(amount).bind(), arbSideFor(amount).bind())
}
