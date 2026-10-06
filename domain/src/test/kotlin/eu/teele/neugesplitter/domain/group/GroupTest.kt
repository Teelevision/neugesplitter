package eu.teele.neugesplitter.domain.group

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.TODAY
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.entryId
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.event.GroupChanges
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.EntryDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupTest {

    private val thirty = Amount.parse("3000000000")

    private fun threePeople() = Log().create().add(ANNA, "Anna").add(BEN, "Ben").add(CLARA, "Clara")

    @Test
    fun `a log replays to the group it describes (TR-19)`() {
        val group = threePeople().replay()
        assertEquals(GROUP, group.id)
        assertEquals(listOf("Anna", "Ben", "Clara"), group.members.values.map { it.name })
    }

    @Test
    fun `a log that does not begin with group_created cannot be read (format-spec 4_2)`() {
        val events = threePeople().events()
        assertFailsWith<Malformed> { Group.replay(events.drop(1)) }
        assertFailsWith<Malformed> { Group.replay(emptyList()) }
    }

    @Test
    fun `an update names only the fields it changes (format-spec 4_2)`() {
        val group =
            threePeople()
                .update(GroupChanges(description = Change.To("Flat 3")))
                .update(GroupChanges(colour = Change.To(Colour.parse("#4C7A3F"))))
                .replay()
        assertEquals("Test", group.name)
        assertEquals("Flat 3", group.description)
        assertEquals(Colour.parse("#4C7A3F"), group.colour)
    }

    @Test
    fun `a field set to null is cleared (format-spec 4_2)`() {
        val group =
            threePeople()
                .update(GroupChanges(description = Change.To("Flat 3")))
                .update(GroupChanges(description = Change.To(null)))
                .replay()
        assertNull(group.description)
    }

    @Test
    fun `a group with no default participants splits between everyone (SP-8)`() {
        assertEquals(listOf(ANNA, BEN, CLARA), threePeople().replay().participants())
    }

    @Test
    fun `default participants can be set once there are members to name (SP-8)`() {
        val group =
            threePeople()
                .update(GroupChanges(defaultParticipants = Change.To(listOf(ANNA, BEN))))
                .replay()
        assertEquals(listOf(ANNA, BEN), group.participants())
    }

    @Test
    fun `clearing the default participants puts everyone back (SP-8)`() {
        val group =
            threePeople()
                .update(GroupChanges(defaultParticipants = Change.To(listOf(ANNA))))
                .update(GroupChanges(defaultParticipants = Change.To(null)))
                .replay()
        assertEquals(listOf(ANNA, BEN, CLARA), group.participants())
    }

    @Test
    fun `a default participant who is not a member cannot be named (format-spec 4_2)`() {
        assertFailsWith<Malformed> {
            Log()
                .create()
                .add(ANNA, "Anna")
                .update(GroupChanges(defaultParticipants = Change.To(listOf(ANNA, BEN))))
                .replay()
        }
    }

    @Test
    fun `a removed member is no longer a default participant (GR-8)`() {
        val group =
            threePeople()
                .update(GroupChanges(defaultParticipants = Change.To(listOf(ANNA, CLARA))))
                .remove(CLARA)
                .replay()
        assertEquals(listOf(ANNA), group.participants())
    }

    @Test
    fun `removal is a flag and leaves the member in place (GR-9)`() {
        val group = threePeople().remove(CLARA).replay()
        assertEquals("Clara", group.members.getValue(CLARA).name)
        assertEquals(setOf(CLARA), group.removed)
    }

    @Test
    fun `a rename keeps the identifier (GR-7)`() {
        val group = threePeople().rename(BEN, "Benjamin").replay()
        assertEquals("Benjamin", group.members.getValue(BEN).name)
    }

    @Test
    fun `an event naming an unknown member cannot be read (format-spec 8)`() {
        assertFailsWith<Malformed> { Log().create().rename(ANNA, "Anna").replay() }
    }

    @Test
    fun `an entry naming a member the group does not have cannot be read (format-spec 8)`() {
        val log = Log().create().add(ANNA, "Anna")
        assertFailsWith<Malformed> {
            log.record(entry(1, thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(BEN)))).replay()
        }
    }

    @Test
    fun `a side that cannot be split cannot be read (format-spec 7)`() {
        val log = threePeople()
        val wrong = Side.Absolute(mapOf(ANNA to Amount.parse("1"), BEN to Amount.parse("1")))
        assertFailsWith<Malformed> {
            log.record(entry(1, thirty, Side.Equal(listOf(ANNA)), wrong)).replay()
        }
    }

    @Test
    fun `two updates to one entry settle each field on its own (event-model 4)`() {
        val group =
            threePeople()
                .record(
                    entry(1, thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, BEN, CLARA)))
                )
                .update(entryId(1), EntryChanges(amount = Change.To(Amount.parse("4500000000"))))
                .update(entryId(1), EntryChanges(date = Change.To(EntryDate.parse("2026-09-01"))))
                .replay()
        val updated = group.entries.getValue(entryId(1))
        assertEquals(Amount.parse("4500000000"), updated.amount)
        assertEquals(EntryDate.parse("2026-09-01"), updated.date)
        assertEquals("Entry 1", updated.description)
    }

    @Test
    fun `replacing a side replaces it entire (event-model 4)`() {
        val group =
            threePeople()
                .record(
                    entry(1, thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, BEN, CLARA)))
                )
                .update(
                    entryId(1),
                    EntryChanges(
                        beneficiaries = Change.To(Side.Shares(mapOf(ANNA to 2L, BEN to 1L)))
                    ),
                )
                .replay()
        assertEquals(
            Side.Shares(mapOf(ANNA to 2L, BEN to 1L)),
            group.entries.getValue(entryId(1)).beneficiaries,
        )
    }

    @Test
    fun `a deleted entry leaves the entry list but not the log (format-spec 4_2)`() {
        val group =
            threePeople()
                .record(
                    entry(1, thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, BEN, CLARA)))
                )
                .delete(entryId(1))
                .replay()
        assertTrue(group.entries.isEmpty())
        assertEquals(setOf(entryId(1)), group.deleted.keys)
        assertTrue(group.balances(TODAY).values.all { it.isZero })
    }

    @Test
    fun `an update after a deletion resurrects the entry (HI-9)`() {
        val group =
            threePeople()
                .record(
                    entry(1, thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, BEN, CLARA)))
                )
                .delete(entryId(1))
                .update(entryId(1), EntryChanges(description = Change.To("Einkauf")))
                .replay()
        assertEquals("Einkauf", group.entries.getValue(entryId(1)).description)
        assertTrue(group.deleted.isEmpty())
    }

    @Test
    fun `an expense leaves the payer in credit and the others in debt (BA-1)`() {
        val group =
            threePeople()
                .record(
                    entry(1, thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, BEN, CLARA)))
                )
                .replay()
        assertEquals(Amount.parse("2000000000"), group.balances(TODAY).getValue(ANNA))
        assertEquals(Amount.parse("-1000000000"), group.balances(TODAY).getValue(BEN))
        assertEquals(Amount.parse("-1000000000"), group.balances(TODAY).getValue(CLARA))
    }

    @Test
    fun `an income reverses the sign (event-model 3)`() {
        val group =
            threePeople()
                .record(
                    entry(
                        1,
                        thirty,
                        Side.Equal(listOf(ANNA)),
                        Side.Equal(listOf(ANNA, BEN, CLARA)),
                        kind = Kind.INCOME,
                    )
                )
                .replay()
        assertEquals(Amount.parse("-2000000000"), group.balances(TODAY).getValue(ANNA))
        assertEquals(Amount.parse("1000000000"), group.balances(TODAY).getValue(BEN))
    }

    @Test
    fun `a transfer moves balance from one member to another (EN-3)`() {
        val group =
            threePeople()
                .record(
                    entry(
                        1,
                        thirty,
                        Side.Equal(listOf(ANNA)),
                        Side.Equal(listOf(BEN)),
                        kind = Kind.TRANSFER,
                    )
                )
                .replay()
        assertEquals(thirty, group.balances(TODAY).getValue(ANNA))
        assertEquals(-thirty, group.balances(TODAY).getValue(BEN))
        assertEquals(Amount.ZERO, group.balances(TODAY).getValue(CLARA))
    }

    @Test
    fun `balances cover every member, including those with nothing to settle (BA-2)`() {
        val group = threePeople().replay()
        assertEquals(setOf(ANNA, BEN, CLARA), group.balances(TODAY).keys)
        assertEquals(setOf(ANNA, BEN, CLARA), group.settlement(TODAY).keys)
    }
}
