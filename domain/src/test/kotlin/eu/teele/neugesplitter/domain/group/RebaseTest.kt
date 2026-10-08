package eu.teele.neugesplitter.domain.group

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.AT
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.entryId
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RebaseTest {

    private fun euros(n: Long) = Amount.ofUnits(n * 100_000_000)

    private val both = listOf(ANNA, BEN)

    /** A 30 € entry split equally between Anna and Ben, as both installations hold it. */
    private val synced =
        Log()
            .create()
            .add(ANNA, "Anna")
            .add(BEN, "Ben")
            .record(entry(1, euros(30), Side.Equal(listOf(ANNA)), Side.Equal(both)))
    private val events = synced.events()
    private val from = synced.replay()

    private val absolute =
        EntryChanges(
            beneficiaries = Change.To(Side.Absolute(mapOf(ANNA to euros(15), BEN to euros(15))))
        )

    private fun update(id: Int, changes: EntryChanges) =
        Event.EntryUpdated(EventId.parse("%032x".format(0x100 + id)), ANNA, AT, entryId(1), changes)

    @Test
    fun `waiting events that still apply are kept as written (sync-design 4)`() {
        val onto = from + update(1, EntryChanges(description = Change.To("Dinner")))
        val waiting = listOf(update(2, EntryChanges(amount = Change.To(euros(40)))))
        assertEquals(waiting, Group.rebase(waiting, from, onto))
    }

    @Test
    fun `an update that no longer applies is restated as its writer saw it (sync-design 4)`() {
        val onto = from + update(1, absolute)
        val waiting = listOf(update(2, EntryChanges(amount = Change.To(euros(40)))))
        assertFailsWith<Malformed> { onto + waiting.single() }

        val rebased = Group.rebase(waiting, from, onto).single() as Event.EntryUpdated
        assertEquals(waiting.single().id, rebased.id)
        assertEquals(
            EntryChanges(
                amount = Change.To(euros(40)),
                beneficiaries = Change.To(Side.Equal(both)),
            ),
            rebased.entry,
        )
        assertEquals((from + waiting.single()).entries, (onto + rebased).entries)
    }

    @Test
    fun `restating covers only the split, so other concurrent changes survive (sync-design 4)`() {
        val onto = from + update(1, absolute.copy(description = Change.To("Pizza")))
        val waiting =
            listOf(
                update(2, EntryChanges(amount = Change.To(euros(40)), note = Change.To("Receipt")))
            )

        val rebased = Group.rebase(waiting, from, onto).single() as Event.EntryUpdated
        assertEquals(
            EntryChanges(
                amount = Change.To(euros(40)),
                beneficiaries = Change.To(Side.Equal(both)),
                note = Change.To("Receipt"),
            ),
            rebased.entry,
        )
        val entry = (onto + rebased).entries.getValue(entryId(1))
        assertEquals("Pizza", entry.description)
        assertEquals("Receipt", entry.note)
        assertEquals(Side.Equal(both), entry.beneficiaries)
    }

    @Test
    fun `later waiting events follow the restated one`() {
        val onto = from + update(1, absolute)
        val waiting =
            listOf(
                update(2, EntryChanges(amount = Change.To(euros(40)))),
                update(3, EntryChanges(description = Change.To("Dinner"))),
            )
        val rebased = Group.rebase(waiting, from, onto)
        assertEquals(waiting[1], rebased[1])
        val entry = rebased.fold(onto, Group::plus).entries.getValue(entryId(1))
        assertEquals(euros(40), entry.amount)
        assertEquals("Dinner", entry.description)
    }

    @Test
    fun `a group created twice is not rebased (format-spec 4_2)`() {
        val waiting = Log().create().events()
        assertFailsWith<Malformed> { Group.rebase(waiting, null, Group.replay(events.take(1))) }
    }
}
