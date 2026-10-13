package eu.teele.neugesplitter.domain.history

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.AT
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.RENT
import eu.teele.neugesplitter.domain.date
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.entryId
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Stop
import eu.teele.neugesplitter.domain.segment
import eu.teele.neugesplitter.domain.template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupHistoryTest {

    private val thirty = Amount.parse("3000000000")

    private fun dinner() =
        entry(
            1,
            thirty,
            Side.Equal(listOf(ANNA)),
            Side.Equal(listOf(ANNA, BEN)),
            description = "Dinner",
        )

    @Test
    fun `every change to a group is listed oldest first, with who made it and when (HI-2)`() {
        val log =
            Log()
                .create()
                .add(ANNA, "Anna")
                .add(BEN, "Ben")
                .update(GroupChanges(name = Change.To("Trip")))
                .rename(BEN, "Benjamin")
                .record(dinner())
                .update(entryId(1), EntryChanges(description = Change.To("Supper")))
                .delete(entryId(1))
                .update(entryId(1), EntryChanges(amount = Change.To(thirty + thirty)))
                .remove(BEN)
        val history = history(log.events())

        assertEquals(
            listOf(
                Happened.Created,
                Happened.MemberAdded(ANNA),
                Happened.MemberAdded(BEN),
                setOf(Setting.NAME),
                Happened.MemberRenamed(BEN, "Ben", "Benjamin"),
                Triple(entryId(1), Revision.CREATED, "Dinner"),
                Triple(entryId(1), Revision.EDITED, "Supper"),
                Triple(entryId(1), Revision.DELETED, "Supper"),
                Triple(entryId(1), Revision.RESTORED, "Supper"),
                Happened.MemberRemoved(BEN),
            ),
            history.map {
                when (val happened = it.happened) {
                    is Happened.Changed -> happened.settings
                    is Happened.Entry ->
                        Triple(happened.entry, happened.change, happened.description)
                    else -> happened
                }
            },
        )
        assertTrue(history.all { it.by == ANNA && it.at == AT })
    }

    @Test
    fun `a change holds what was before and after, so that it can say what changed (HI-2)`() {
        val log =
            Log()
                .create(name = "Test")
                .add(ANNA, "Anna")
                .add(BEN, "Ben")
                .update(GroupChanges(name = Change.To("Trip")))
                .record(dinner())
                .update(entryId(1), EntryChanges(amount = Change.To(thirty + thirty)))
                .delete(entryId(1))
        val history = history(log.events()).map { it.happened }

        val renamed = history[3] as Happened.Changed
        assertEquals("Test" to "Trip", renamed.before.name to renamed.after.name)
        assertEquals(Happened.Entry(entryId(1), Revision.CREATED, null, dinner()), history[4])
        val doubled = dinner().copy(amount = thirty + thirty)
        assertEquals(Happened.Entry(entryId(1), Revision.EDITED, dinner(), doubled), history[5])
        assertEquals(Happened.Entry(entryId(1), Revision.DELETED, doubled, doubled), history[6])
    }

    @Test
    fun `a member who adds themselves is told apart by who made the change (HI-2, BE-6)`() {
        val log = Log().create().add(ANNA, "Anna")
        val joined =
            Event.MemberAdded(EventId.parse("%032x".format(99)), BEN, AT, Member(BEN, "Ben"))
        val last = history(log.events() + joined).last()
        assertEquals(Happening(Happened.MemberAdded(BEN), BEN, AT), last)
    }

    @Test
    fun `several settings changed at once are named together (HI-2)`() {
        val log =
            Log()
                .create()
                .add(ANNA, "Anna")
                .update(
                    GroupChanges(
                        emoji = Change.To(null),
                        description = Change.To("Summer"),
                        defaultParticipants = Change.To(listOf(ANNA)),
                    )
                )
        assertEquals(
            setOf(Setting.EMOJI, Setting.DESCRIPTION, Setting.DEFAULT_PARTICIPANTS),
            (history(log.events()).last().happened as Happened.Changed).settings,
        )
    }

    @Test
    fun `recurring series and their occurrences are listed with what became of them (HI-2, RE-1)`() {
        val rent =
            segment(
                Interval.EveryDays(30),
                template(thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, CLARA))),
            )
        val dearer = rent.copy(template = rent.template.copy(amount = thirty + thirty))
        val log =
            Log()
                .create()
                .add(ANNA, "Anna")
                .add(CLARA, "Clara")
                .start(RENT, "2026-08-01", rent)
                .confirm(RENT, "2026-08-01")
                .amend(RENT, "2026-08-31", dearer)
                .amend(RENT, "2026-08-31", "2026-09-02", dearer.template)
                .decline(RENT, "2026-09-30")
                .stop(RENT, "2026-10-01", "2026-11-01")
                .resume(RENT, "2026-10-15")
                .delete(RENT)
        val due = date("2026-08-31")
        assertEquals(
            listOf(
                SeriesChange.Started(date("2026-08-01"), rent),
                SeriesChange.Confirmed(date("2026-08-01")),
                SeriesChange.Amended(due, rent, dearer),
                SeriesChange.Edited(
                    due,
                    dearer.template.on(EntryId.occurrence(RENT, due), due),
                    dearer.template.on(EntryId.occurrence(RENT, due), date("2026-09-02")),
                ),
                SeriesChange.LeftOut(date("2026-09-30")),
                SeriesChange.Stopped(Stop(date("2026-10-01"), date("2026-11-01"))),
                SeriesChange.Continued(Stop(date("2026-10-15"))),
                SeriesChange.Deleted,
            ),
            history(log.events()).drop(3).map {
                val happened = it.happened as Happened.Recurring
                assertEquals(RENT to "Rent", happened.series to happened.description)
                happened.change
            },
        )
    }

    @Test
    fun `an empty log has no history`() {
        assertEquals(emptyList(), history(emptyList()))
    }

    private val later = AT.plusSeconds(1)

    private val monthly =
        segment(
            Interval.EveryDays(30),
            template(thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, BEN))),
        )

    private fun deleted(n: Int, description: String): Happened.Entry {
        val entry = dinner().copy(id = entryId(n), description = description)
        return Happened.Entry(entryId(n), Revision.DELETED, entry, entry)
    }

    private val started =
        Happened.Recurring(RENT, "Rent", SeriesChange.Started(date("2026-08-01"), monthly))

    private val confirmed =
        Happened.Recurring(RENT, "Rent", SeriesChange.Confirmed(date("2026-08-01")))

    private fun settings(emoji: String?, colour: Colour?) =
        Settings(Scale.DEFAULT, "Test", null, emoji, colour, null)

    @Test
    fun `an entry deleted for a series at the same moment is one step, made recurring (HI-2, RE-1)`() {
        val history =
            listOf(
                Happening(Happened.Created, ANNA, AT),
                Happening(deleted(1, "Rent"), ANNA, later),
                Happening(started, ANNA, later),
                Happening(confirmed, ANNA, later),
            )
        assertEquals(
            listOf(
                Step(Happening(Happened.Created, ANNA, AT), 0..0),
                Step(
                    Happening(
                        Happened.MadeRecurring(entryId(1), "Rent", RENT, monthly),
                        ANNA,
                        later,
                    ),
                    1..3,
                ),
            ),
            steps(history),
        )
    }

    @Test
    fun `settings changed at one moment are one step naming them all (HI-2)`() {
        val red = Colour.parse("#c62828")
        val history =
            listOf(
                Happening(
                    Happened.Changed(
                        setOf(Setting.EMOJI),
                        settings(null, null),
                        settings("⛰", null),
                    ),
                    ANNA,
                    AT,
                ),
                Happening(
                    Happened.Changed(
                        setOf(Setting.COLOUR),
                        settings("⛰", null),
                        settings("⛰", red),
                    ),
                    ANNA,
                    AT,
                ),
            )
        assertEquals(
            listOf(
                Step(
                    Happening(
                        Happened.Changed(
                            setOf(Setting.EMOJI, Setting.COLOUR),
                            settings(null, null),
                            settings("⛰", red),
                        ),
                        ANNA,
                        AT,
                    ),
                    0..1,
                )
            ),
            steps(history),
        )
    }

    @Test
    fun `changes at other moments, by others, or telling different things stay apart (HI-2)`() {
        val other = SeriesId.parse("%032x".format(0x5e2))
        val history =
            listOf(
                Happening(started, ANNA, AT),
                Happening(confirmed, ANNA, later),
                Happening(confirmed, BEN, later),
                Happening(deleted(1, "Rent"), BEN, later),
                Happening(deleted(2, "Taxi"), BEN, later),
                Happening(confirmed, ANNA, later),
                Happening(confirmed.copy(series = other), ANNA, later),
            )
        assertEquals(history.indices.map { it..it }, steps(history).map { it.events })
    }
}
