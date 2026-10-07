package eu.teele.neugesplitter.domain.history

import eu.teele.neugesplitter.domain.ANNA
import eu.teele.neugesplitter.domain.AT
import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.CLARA
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.entryId
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.value.Amount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryHistoryTest {

    private val thirty = Amount.parse("3000000000")
    private val forty = Amount.parse("4000000000")

    private fun dinner() =
        entry(1, thirty, Side.Equal(listOf(ANNA)), Side.Equal(listOf(ANNA, BEN, CLARA)))

    private fun people() = Log().create().add(ANNA, "Anna").add(BEN, "Ben").add(CLARA, "Clara")

    @Test
    fun `an entry's life is listed oldest first, a restoration apart from an edit (HI-3)`() {
        val log =
            people()
                .record(dinner())
                .update(entryId(1), EntryChanges(amount = Change.To(forty)))
                .delete(entryId(1))
                .update(entryId(1), EntryChanges(description = Change.To("Supper")))
                .update(entryId(1), EntryChanges(note = Change.To("Late")))
        val versions = versions(log.events(), entryId(1))

        assertEquals(
            listOf(
                Revision.CREATED,
                Revision.EDITED,
                Revision.DELETED,
                Revision.RESTORED,
                Revision.EDITED,
            ),
            versions.map { it.change },
        )
        assertTrue(versions.all { it.by == ANNA && it.at == AT })
        val (created, edited, deleted, restored, noted) = versions
        assertNull(created.before)
        assertEquals(dinner(), created.after)
        assertEquals(dinner(), edited.before)
        assertEquals(forty, edited.after.amount)
        assertEquals(edited.after, deleted.before)
        assertEquals(edited.after, deleted.after)
        assertEquals(edited.after, restored.before)
        assertEquals("Supper", restored.after.description)
        assertEquals(restored.after, noted.before)
        assertEquals("Late", noted.after.note)
    }

    @Test
    fun `other entries and group events are not part of an entry's history (HI-3)`() {
        val other = entry(2, forty, Side.Equal(listOf(BEN)), Side.Equal(listOf(ANNA, BEN)))
        val log =
            people()
                .record(dinner())
                .record(other)
                .update(entryId(2), EntryChanges(amount = Change.To(thirty)))
                .delete(entryId(2))
                .rename(BEN, "Benjamin")
        assertEquals(listOf(Revision.CREATED), versions(log.events(), entryId(1)).map { it.change })
        assertEquals(
            listOf(Revision.CREATED, Revision.EDITED, Revision.DELETED),
            versions(log.events(), entryId(2)).map { it.change },
        )
    }

    @Test
    fun `an entry the log does not know has no history (HI-3)`() {
        val log = people().record(dinner())
        assertEquals(emptyList(), versions(log.events(), entryId(7)))
        assertEquals(emptyList(), versions(emptyList(), entryId(1)))
    }
}
