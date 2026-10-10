package eu.teele.neugesplitter.domain.history

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.AT
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.RENT
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.entryId
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.segment
import eu.teele.neugesplitter.domain.template
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EventId
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
                Happened.Changed(setOf(Setting.NAME)),
                Happened.MemberRenamed(BEN, "Ben", "Benjamin"),
                Happened.Entry(entryId(1), Revision.CREATED, "Dinner"),
                Happened.Entry(entryId(1), Revision.EDITED, "Supper"),
                Happened.Entry(entryId(1), Revision.DELETED, "Supper"),
                Happened.Entry(entryId(1), Revision.RESTORED, "Supper"),
                Happened.MemberRemoved(BEN),
            ),
            history.map { it.happened },
        )
        assertTrue(history.all { it.by == ANNA && it.at == AT })
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
            Happened.Changed(
                setOf(Setting.EMOJI, Setting.DESCRIPTION, Setting.DEFAULT_PARTICIPANTS)
            ),
            history(log.events()).last().happened,
        )
    }

    @Test
    fun `recurring series and their occurrences are listed as such (HI-2, RE-1)`() {
        val rent =
            segment(
                Interval.EveryDays(30),
                template(thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, CLARA))),
            )
        val log =
            Log()
                .create()
                .add(ANNA, "Anna")
                .add(CLARA, "Clara")
                .start(RENT, "2026-08-01", rent)
                .confirm(RENT, "2026-08-01")
                .stop(RENT, "2026-09-01")
                .resume(RENT, "2026-10-01")
        assertEquals(
            List(4) { Happened.Recurring },
            history(log.events()).drop(3).map { it.happened },
        )
    }

    @Test
    fun `an empty log has no history`() {
        assertEquals(emptyList(), history(emptyList()))
    }
}
