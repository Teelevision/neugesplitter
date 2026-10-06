package eu.teele.neugesplitter.domain

import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map

val POOL: List<MemberId> = (1..5).map { member(it) }

val arbScale: Arb<Scale> = Arb.int(0..8).map { Scale.of(it) }

val arbAmount: Arb<Amount> = Arb.long(1L..100_000_000_000L).map { Amount.ofUnits(it) }

/** A side valid for [amount]: `absolute` and `adjustments` are only ever valid for one amount. */
fun arbSideFor(amount: Amount): Arb<Side> = arbitrary {
    val members = POOL.take(Arb.int(1..POOL.size).bind())
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

val arbSplit: Arb<Pair<Amount, Side>> = arbitrary {
    val amount = arbAmount.bind()
    amount to arbSideFor(amount).bind()
}

/** A whole group, built the way a real one is: a log of events, replayed. */
val arbGroup: Arb<Group> = arbitrary {
    val log = Log().create(scale = arbScale.bind())
    POOL.forEachIndexed { index, id -> log.add(id, "Member ${index + 1}") }
    repeat(Arb.int(1..8).bind()) { index ->
        val amount = arbAmount.bind()
        log.record(
            entry(
                n = index + 1,
                amount = amount,
                payers = arbSideFor(amount).bind(),
                beneficiaries = arbSideFor(amount).bind(),
                kind = Arb.enum<Kind>().bind(),
            )
        )
    }
    log.replay()
}
