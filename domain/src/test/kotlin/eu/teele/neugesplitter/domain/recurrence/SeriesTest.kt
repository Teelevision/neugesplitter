package eu.teele.neugesplitter.domain.recurrence

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.NORA
import eu.teele.neugesplitter.domain.RENT
import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.date
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.segment
import eu.teele.neugesplitter.domain.template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recurring series as a replayed group sees them (format-spec 4.2, event-model 6). TODAY is 31 Aug.
 */
class SeriesTest {

    private val euro = 100_000_000L
    private val everyone = Side.Equal(listOf(ANNA, BEN, CLARA))

    private fun rent(euros: Long) =
        template(Amount.ofUnits(euros * euro), Side.Equal(listOf(ANNA)), everyone)

    private fun monthly(euros: Long, mode: Mode = Mode.AUTOMATIC) =
        segment(Interval.MonthlyOn(setOf(1)), rent(euros), mode)

    private fun threePeople() = Log().create().add(ANNA, "Anna").add(BEN, "Ben").add(CLARA, "Clara")

    private fun Log.dues() = replay().occurrences(TODAY).map { it.due.toString() }

    private fun Log.anna() = replay().balances(TODAY).getValue(ANNA).units / euro

    @Test
    fun `each due date up to today yields exactly one occurrence (RE-8)`() {
        val log = threePeople().start(RENT, "2026-06-01", monthly(900))
        assertEquals(listOf("2026-06-01", "2026-07-01", "2026-08-01"), log.dues())
        assertEquals(1800, log.anna())
    }

    @Test
    fun `an occurrence appears once the local date reaches it, not before (RE-14)`() {
        val group = threePeople().start(RENT, "2026-06-01", monthly(900)).replay()
        assertEquals(2, group.occurrences(date("2026-07-31")).size)
        assertEquals(3, group.occurrences(date("2026-08-01")).size)
    }

    @Test
    fun `an occurrence has the derived identifier of format-spec 6`() {
        val group = threePeople().start(RENT, "2026-08-01", monthly(900)).replay()
        val id = group.occurrences(TODAY).single().entry.id
        assertEquals(EntryId.parse("61e4d5cd03317a246fe4e51620dfdaa0"), id)
        assertEquals(id.hex, EventId.occurrence(RENT, date("2026-08-01")).hex)
    }

    @Test
    fun `a suggested occurrence stays out of the balances until it is confirmed (RE-4)`() {
        val log = threePeople().start(RENT, "2026-07-01", monthly(900, Mode.SUGGESTED))
        assertTrue(log.replay().occurrences(TODAY).all { it.pending })
        assertEquals(0, log.anna())

        log.confirm(RENT, "2026-08-01")
        assertEquals(600, log.anna())
        assertEquals(listOf(true, false), log.replay().occurrences(TODAY).map { it.pending })
    }

    @Test
    fun `deleting one occurrence leaves the series running (RE-5, RE-15)`() {
        val log = threePeople().start(RENT, "2026-06-01", monthly(900)).decline(RENT, "2026-07-01")
        assertEquals(listOf("2026-06-01", "2026-08-01"), log.dues())
        assertEquals(1200, log.anna())
    }

    @Test
    fun `an amended occurrence carries its own date and is still the one due on the first (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-08-01", monthly(900))
                .amend(RENT, due = "2026-08-01", on = "2026-08-03", rent(930))
        val occurrence = log.replay().occurrences(TODAY).single()
        assertEquals(date("2026-08-01"), occurrence.due)
        assertEquals(date("2026-08-03"), occurrence.entry.date)
        assertEquals(620, log.anna())
    }

    @Test
    fun `the last occurrence event written is the occurrence's state (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-08-01", monthly(900, Mode.SUGGESTED))
                .confirm(RENT, "2026-08-01")
                .decline(RENT, "2026-08-01")
        assertEquals(emptyList(), log.dues())

        log.amend(RENT, due = "2026-08-01", on = "2026-08-01", rent(600))
        assertEquals(400, log.anna())
    }

    @Test
    fun `amending a series reaches forward and leaves what was due before it alone (RE-6)`() {
        val log =
            threePeople()
                .start(RENT, "2026-06-01", monthly(900))
                .amend(RENT, "2026-08-01", monthly(1200))
        val amounts = log.replay().occurrences(TODAY).map { it.entry.amount.units / euro }
        assertEquals(listOf(900L, 900L, 1200L), amounts)
    }

    @Test
    fun `an amendment restarts every n days at its own date (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-08-01", segment(Interval.EveryDays(10), rent(30)))
                .amend(RENT, "2026-08-15", segment(Interval.EveryDays(10), rent(30)))
        assertEquals(listOf("2026-08-01", "2026-08-11", "2026-08-15", "2026-08-25"), log.dues())
    }

    @Test
    fun `a stop takes its days out of the schedule, and continuing puts them back (RE-7)`() {
        val log = threePeople().start(RENT, "2026-05-01", monthly(900)).stop(RENT, "2026-06-15")
        assertEquals(listOf("2026-05-01", "2026-06-01"), log.dues())

        log.resume(RENT, "2026-08-01")
        assertEquals(listOf("2026-05-01", "2026-06-01", "2026-08-01"), log.dues())
    }

    @Test
    fun `a pause ends by itself on the day it continues (RE-7)`() {
        val log =
            threePeople()
                .start(RENT, "2026-05-01", monthly(900))
                .stop(RENT, "2026-05-15", until = "2026-07-01")
        assertEquals(listOf("2026-05-01", "2026-07-01", "2026-08-01"), log.dues())
    }

    @Test
    fun `an amendment does not end a stop, and goes on once the stop does (RE-6, RE-7)`() {
        val log =
            threePeople()
                .start(RENT, "2026-05-01", monthly(900))
                .stop(RENT, "2026-05-15", until = "2026-07-15")
                .amend(RENT, "2026-06-10", monthly(1200))
        val amounts = log.replay().occurrences(TODAY).map { it.entry.amount.units / euro }
        assertEquals(listOf("2026-05-01", "2026-08-01"), log.dues())
        assertEquals(listOf(900L, 1200L), amounts)
    }

    @Test
    fun `overlapping pauses join into one (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-05-01", monthly(900))
                .stop(RENT, "2026-05-15", until = "2026-06-15")
                .stop(RENT, "2026-06-10", until = "2026-07-15")
                .stop(RENT, "2026-07-15", until = "2026-07-20")
        val series = log.replay().series.getValue(RENT)
        assertEquals(listOf(Stop(date("2026-05-15"), date("2026-07-20"))), series.stops)
        assertEquals(listOf("2026-05-01", "2026-08-01"), log.dues())
    }

    @Test
    fun `continuing within a stop splits it, and a stop for good swallows a pause (RE-7)`() {
        val log =
            threePeople()
                .start(RENT, "2026-05-01", monthly(900))
                .stop(RENT, "2026-05-15")
                .resume(RENT, "2026-06-01", until = "2026-07-02")
        val split = log.replay().series.getValue(RENT).stops
        assertEquals(
            listOf(Stop(date("2026-05-15"), date("2026-06-01")), Stop(date("2026-07-02"))),
            split,
        )
        assertEquals(listOf("2026-05-01", "2026-06-01", "2026-07-01"), log.dues())

        log.stop(RENT, "2026-05-10")
        assertEquals(listOf(Stop(date("2026-05-10"))), log.replay().series.getValue(RENT).stops)
    }

    @Test
    fun `the stop that counts is the one in force today or else the next ahead (RE-7)`() {
        val log =
            threePeople()
                .start(RENT, "2026-05-01", monthly(900))
                .stop(RENT, "2026-06-01", until = "2026-06-15")
                .stop(RENT, "2026-09-01", until = "2026-10-01")
        val series = log.replay().series.getValue(RENT)
        assertEquals(Stop(date("2026-09-01"), date("2026-10-01")), series.stop(TODAY))
        assertEquals(Stop(date("2026-06-01"), date("2026-06-15")), series.stop(date("2026-06-14")))
        assertEquals(null, series.stop(date("2026-10-01")))
    }

    @Test
    fun `a stop ending on or before its first day is malformed (format-spec 4_2)`() {
        val log = threePeople().start(RENT, "2026-05-01", monthly(900))
        assertFailsWith<Malformed> { log.stop(RENT, "2026-06-01", until = "2026-06-01").replay() }
    }

    @Test
    fun `a confirmed or amended occurrence stands through a stop (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-06-01", monthly(900, Mode.SUGGESTED))
                .confirm(RENT, "2026-06-01")
                .amend(RENT, due = "2026-07-01", on = "2026-07-02", rent(930))
                .stop(RENT, "2026-06-01")
        assertEquals(listOf("2026-06-01", "2026-07-01"), log.dues())
        assertEquals(600 + 620, log.anna())
    }

    @Test
    fun `the series knows whether anybody confirmed or edited an occurrence after a date (RE-6)`() {
        val log =
            threePeople()
                .start(RENT, "2026-06-01", monthly(900, Mode.SUGGESTED))
                .confirm(RENT, "2026-07-01")
                .decline(RENT, "2026-08-01")
        val series = log.replay().series.getValue(RENT)
        assertTrue(series.decidedAfter(date("2026-06-01")))
        assertFalse(series.decidedAfter(date("2026-07-01")))
    }

    @Test
    fun `the next due date is the schedule's, stops or not (RE-7)`() {
        val series =
            threePeople()
                .start(RENT, "2026-05-01", monthly(900))
                .stop(RENT, "2026-06-15")
                .replay()
                .series
                .getValue(RENT)
        assertEquals(date("2026-05-01"), series.nextDue(date("2026-05-01")))
        assertEquals(date("2026-10-01"), series.nextDue(date("2026-09-02")))
    }

    @Test
    fun `of two segments from the same date the later in the log is in force (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-08-01", monthly(900))
                .amend(RENT, "2026-08-01", monthly(600))
        assertEquals(400, log.anna())
    }

    @Test
    fun `the segment in force on a date is the latest from on or before it, stopped or not (RE-6)`() {
        val series =
            threePeople()
                .start(RENT, "2026-05-01", monthly(900))
                .stop(RENT, "2026-06-15")
                .amend(RENT, "2026-08-01", monthly(1200))
                .amend(RENT, "2026-08-01", monthly(1300))
                .replay()
                .series
                .getValue(RENT)
        assertEquals(null, series.segment(date("2026-04-30")))
        assertEquals(monthly(900), series.segment(date("2026-07-31")))
        assertEquals(monthly(1300), series.segment(TODAY))
        assertEquals(monthly(900), series.current(date("2026-04-01")))
    }

    @Test
    fun `an occurrence is found by the id it has in the entry list (format-spec 6)`() {
        val group = threePeople().start(RENT, "2026-07-01", monthly(900)).replay()
        val id = EntryId.occurrence(RENT, date("2026-08-01"))
        assertEquals(date("2026-08-01"), group.occurrence(id, TODAY)?.due)
        assertEquals(null, group.occurrence(id, date("2026-07-31")))
        assertEquals(rent(900), Template.of(group.occurrence(id, TODAY)!!.entry))
    }

    @Test
    fun `a group without an occurrence still has the rest of its series (RE-15)`() {
        val group = threePeople().start(RENT, "2026-07-01", monthly(900)).replay()
        val without = group.without(EntryId.occurrence(RENT, date("2026-08-01")), TODAY)
        assertEquals(listOf(date("2026-07-01")), without.occurrences(TODAY).map { it.due })
        assertEquals(600, without.balances(TODAY).getValue(ANNA).units / euro)
    }

    @Test
    fun `deleting a series removes everything it produced, and amending it puts it back (RE-7)`() {
        val log = threePeople().start(RENT, "2026-06-01", monthly(900)).delete(RENT)
        assertEquals(emptyList(), log.dues())
        assertEquals(0, log.anna())

        log.amend(RENT, "2026-08-15", monthly(900))
        assertEquals(listOf("2026-06-01", "2026-07-01", "2026-08-01"), log.dues())
    }

    @Test
    fun `a deletion on a date that is not due has no effect, and a confirmation stands (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-07-01", monthly(900))
                .decline(RENT, "2026-08-02")
                .confirm(RENT, "2026-08-03")
                .confirm(RENT, "2026-06-30")
        assertEquals(listOf("2026-07-01", "2026-08-01", "2026-08-03"), log.dues())
        assertEquals(1800, log.anna())
    }

    @Test
    fun `an amended occurrence stands when the schedule moves away from it (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-07-01", monthly(900))
                .amend(RENT, due = "2026-08-01", on = "2026-08-03", rent(930))
                .amend(RENT, "2026-07-31", segment(Interval.MonthlyOn(setOf(2)), rent(900)))
        assertEquals(listOf("2026-07-01", "2026-08-01", "2026-08-02"), log.dues())
        assertEquals(600 + 620 + 600, log.anna())
    }

    @Test
    fun `an amended occurrence is shown once its due date is reached, like any other (RE-14)`() {
        val group =
            threePeople()
                .start(RENT, "2026-08-01", monthly(900))
                .stop(RENT, "2026-08-02")
                .amend(RENT, due = "2026-09-01", on = "2026-09-01", rent(930))
                .replay()
        assertEquals(1, group.occurrences(TODAY).size)
        assertEquals(2, group.occurrences(date("2026-09-01")).size)
    }

    @Test
    fun `only an occurrence confirmed or amended after a deletion outlives it (format-spec 4_2, HI-9)`() {
        val log =
            threePeople()
                .start(RENT, "2026-06-01", monthly(900))
                .amend(RENT, due = "2026-06-01", on = "2026-06-01", rent(930))
                .delete(RENT)
                .amend(RENT, due = "2026-07-01", on = "2026-07-01", rent(600))
                .confirm(RENT, "2026-08-01")
        assertEquals(listOf("2026-07-01", "2026-08-01"), log.dues())
        assertEquals(400 + 600, log.anna())

        log.delete(RENT)
        assertEquals(emptyList(), log.dues())

        log.amend(RENT, "2026-08-15", monthly(900))
        assertEquals(listOf("2026-06-01", "2026-07-01", "2026-08-01"), log.dues())
        assertEquals(620 + 400 + 600, log.anna())
    }

    @Test
    fun `a retrospective amendment changes confirmed occurrences but not amended ones (RE-6)`() {
        val log =
            threePeople()
                .start(RENT, "2026-06-01", monthly(900, Mode.SUGGESTED))
                .confirm(RENT, "2026-06-01")
                .amend(RENT, due = "2026-07-01", on = "2026-07-01", rent(930))
                .amend(RENT, "2026-06-01", monthly(1200, Mode.SUGGESTED))
        val occurrences = log.replay().occurrences(TODAY)
        assertEquals(listOf(1200L, 930L, 1200L), occurrences.map { it.entry.amount.units / euro })
        assertEquals(listOf(false, false, true), occurrences.map { it.pending })
    }

    @Test
    fun `a series naming someone who is not a member is malformed (format-spec 4_2)`() {
        val stranger = template(Amount.ofUnits(euro), Side.Equal(listOf(NORA)), everyone)
        assertFailsWith<Malformed> {
            threePeople()
                .start(RENT, "2026-08-01", segment(Interval.EveryDays(1), stranger))
                .replay()
        }
    }

    @Test
    fun `a series is created once and an occurrence names a series that exists (format-spec 4_2)`() {
        assertFailsWith<Malformed> {
            threePeople()
                .start(RENT, "2026-08-01", monthly(900))
                .start(RENT, "2026-08-01", monthly(900))
                .replay()
        }
        assertFailsWith<Malformed> { threePeople().confirm(RENT, "2026-08-01").replay() }
    }
}
