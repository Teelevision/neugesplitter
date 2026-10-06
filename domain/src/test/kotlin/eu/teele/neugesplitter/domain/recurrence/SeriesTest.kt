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
    fun `ending a series stops it and an amendment after that resumes it (RE-7)`() {
        val log =
            threePeople()
                .start(RENT, "2026-05-01", monthly(900))
                .end(RENT, "2026-06-15")
                .amend(RENT, "2026-08-01", monthly(900))
        assertEquals(listOf("2026-05-01", "2026-06-01", "2026-08-01"), log.dues())
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
    fun `deleting a series removes everything it produced, and amending it puts it back (RE-7)`() {
        val log = threePeople().start(RENT, "2026-06-01", monthly(900)).delete(RENT)
        assertEquals(emptyList(), log.dues())
        assertEquals(0, log.anna())

        log.amend(RENT, "2026-08-15", monthly(900))
        assertEquals(listOf("2026-06-01", "2026-07-01", "2026-08-01"), log.dues())
    }

    @Test
    fun `a confirmation or deletion on a date that is not due is read and has no effect (format-spec 4_2)`() {
        val log =
            threePeople()
                .start(RENT, "2026-08-01", monthly(900))
                .decline(RENT, "2026-08-02")
                .confirm(RENT, "2026-08-03")
        assertEquals(listOf("2026-08-01"), log.dues())
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
                .end(RENT, "2026-08-02")
                .amend(RENT, due = "2026-09-01", on = "2026-09-01", rent(930))
                .replay()
        assertEquals(1, group.occurrences(TODAY).size)
        assertEquals(2, group.occurrences(date("2026-09-01")).size)
    }

    @Test
    fun `only an occurrence amended after a deletion outlives it (format-spec 4_2, HI-9)`() {
        val log =
            threePeople()
                .start(RENT, "2026-06-01", monthly(900))
                .amend(RENT, due = "2026-06-01", on = "2026-06-01", rent(930))
                .delete(RENT)
                .amend(RENT, due = "2026-07-01", on = "2026-07-01", rent(600))
                .confirm(RENT, "2026-08-01")
        assertEquals(listOf("2026-07-01"), log.dues())
        assertEquals(400, log.anna())

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
