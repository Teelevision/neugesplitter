package eu.teele.neugesplitter.domain

import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.recurrence.Decision
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.recurrence.Series
import eu.teele.neugesplitter.domain.recurrence.Stop
import eu.teele.neugesplitter.domain.recurrence.Template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import java.time.DayOfWeek
import java.time.LocalDate

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

/** Dates from 2024 to a month past [TODAY], so that some series have not begun yet. */
val arbDate: Arb<EntryDate> =
    Arb.int(0..1000).map { EntryDate.of(LocalDate.of(2024, 1, 1).plusDays(it.toLong())) }

val arbInterval: Arb<Interval> = arbitrary {
    when (Arb.int(0..2).bind()) {
        0 -> Interval.EveryDays(Arb.int(1..60).bind())
        1 ->
            Interval.WeeklyOn(
                DayOfWeek.entries
                    .filter { Arb.boolean().bind() }
                    .ifEmpty { listOf(DayOfWeek.MONDAY) }
                    .toSet()
            )
        else ->
            Interval.MonthlyOn(
                (1..31).filter { Arb.int(0..9).bind() == 0 }.ifEmpty { listOf(31) }.toSet()
            )
    }
}

val arbTemplate: Arb<Template> = arbitrary {
    val amount = arbAmount.bind()
    Template(
        Arb.enum<Kind>().bind(),
        "Series",
        amount,
        arbSideFor(amount).bind(),
        arbSideFor(amount).bind(),
    )
}

val arbSegment: Arb<Segment> = arbitrary {
    Segment(arbInterval.bind(), Arb.enum<Mode>().bind(), arbTemplate.bind())
}

/** Days stopped from a date, for up to two months or for good. */
val arbStop: Arb<Stop> = arbitrary {
    val from = arbDate.bind()
    val days = Arb.int(0..60).bind()
    Stop(from, if (days == 0) null else EntryDate.of(from.value.plusDays(days.toLong())))
}

/**
 * A series of up to four segments, stopped and continued, with decisions on dates drawn freely and
 * deleted and restored in between.
 */
val arbSeries: Arb<Series> = arbitrary {
    val later = List(Arb.int(0..3).bind()) { arbDate.bind() to arbSegment.bind() }
    var series = Series(RENT, listOf(arbDate.bind() to arbSegment.bind()) + later)
    repeat(Arb.int(0..6).bind()) {
        val due = arbDate.bind()
        series =
            when (Arb.int(0..7).bind()) {
                0 -> series.removed()
                1 -> series.restored()
                2 -> series.decided(due, Decision.Confirmed)
                3 -> series.decided(due, Decision.Deleted)
                4 -> series.stopped(arbStop.bind())
                5 -> series.continued(arbStop.bind())
                else -> series.decided(due, Decision.Amended(arbDate.bind(), arbTemplate.bind()))
            }
    }
    series
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
    repeat(Arb.int(0..2).bind()) { index ->
        val series = SeriesId.parse("%032x".format(0x5e00 + index))
        log.start(series, arbDate.bind().toString(), arbSegment.bind())
        repeat(Arb.int(0..4).bind()) {
            // Dates are drawn freely, so some decisions name a date that is not due.
            val date = arbDate.bind().toString()
            val stop = arbStop.bind()
            when (Arb.int(0..6).bind()) {
                0 -> log.amend(series, date, arbSegment.bind())
                1 -> log.stop(series, stop.from.toString(), stop.until?.toString())
                2 -> log.resume(series, stop.from.toString(), stop.until?.toString())
                3 -> log.confirm(series, date)
                4 -> log.decline(series, date)
                5 -> log.delete(series)
                else -> log.amend(series, date, arbDate.bind().toString(), arbTemplate.bind())
            }
        }
    }
    log.replay()
}
