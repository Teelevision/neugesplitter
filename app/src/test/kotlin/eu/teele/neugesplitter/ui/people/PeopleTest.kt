package eu.teele.neugesplitter.ui.people

import eu.teele.neugesplitter.domain.balance.Transfer
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import kotlin.test.Test
import kotlin.test.assertEquals

class PeopleTest {

    private val today = EntryDate.parse("2026-10-07")
    private val anna = Member(MemberId.parse("%032x".format(1)), "Anna")
    private val ben = Member(MemberId.parse("%032x".format(2)), "Ben")
    private val clara = Member(MemberId.parse("%032x".format(3)), "Clara")
    private val dora = Member(MemberId.parse("%032x".format(4)), "Dora")

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    /** Anna paid 30.00 for herself, Ben and Clara; Dora joined and left without taking part. */
    private val group =
        Group(
            id = GroupId.parse("%032x".format(9)),
            scale = Scale.of(2),
            name = "Trip",
            emoji = "⛺",
            members = listOf(anna, ben, clara, dora).associateBy { it.id },
            entries =
                listOf(
                        Entry(
                            id = EntryId.parse("%032x".format(10)),
                            kind = Kind.EXPENSE,
                            description = "Dinner",
                            date = today,
                            amount = cents(3000),
                            payers = Side.Equal(listOf(anna.id)),
                            beneficiaries = Side.Equal(listOf(anna.id, ben.id, clara.id)),
                        )
                    )
                    .associateBy { it.id },
        )

    @Test
    fun `members are listed largest creditor first, with the transfers that settle them (BA-1, BA-3)`() {
        val state = people(group, ben.id, today)
        assertEquals("⛺ Trip", state.title)
        assertEquals(
            listOf(
                anna.id to cents(2000),
                dora.id to cents(0),
                ben.id to cents(-1000),
                clara.id to cents(-1000),
            ),
            state.people.map { it.id to it.balance },
        )
        assertEquals(listOf(ben.id), state.people.filter { it.me }.map { it.id })
        assertEquals(
            listOf(
                Transfer(ben.id, anna.id, cents(1000)),
                Transfer(clara.id, anna.id, cents(1000)),
            ),
            state.transfers,
        )
    }

    @Test
    fun `a removed member is listed while they appear in an entry (GR-9)`() {
        val state = people(group.copy(removed = setOf(clara.id, dora.id)), anna.id, today)
        assertEquals(listOf(anna.id, ben.id, clara.id), state.people.map { it.id })
        assertEquals(listOf(clara.id), state.people.filter { it.removed }.map { it.id })
    }

    @Test
    fun `a member's entries are those they pay or benefit in, newest first, without deleted ones`() {
        fun entry(n: Int, date: String, payer: Member, vararg beneficiaries: Member) =
            Entry(
                id = EntryId.parse("%032x".format(n)),
                kind = Kind.EXPENSE,
                description = "Entry $n",
                date = EntryDate.parse(date),
                amount = cents(1000),
                payers = Side.Equal(listOf(payer.id)),
                beneficiaries = Side.Equal(beneficiaries.map { it.id }),
            )
        val older = entry(11, "2026-10-01", ben, anna)
        val newer = entry(12, "2026-10-05", anna, ben)
        val unrelated = entry(13, "2026-10-06", anna, clara)
        val deleted = entry(14, "2026-10-07", ben, ben)
        val withMore =
            group.copy(
                entries = group.entries + listOf(older, newer, unrelated).associateBy { it.id },
                deleted = mapOf(deleted.id to deleted),
            )

        fun entries(member: Member) =
            people(withMore, anna.id, today).people.first { it.id == member.id }.entries

        val bens = entries(ben)

        assertEquals(listOf("Dinner", "Entry 12", "Entry 11"), bens.map { it.description })
        assertEquals(listOf(listOf("Anna"), listOf("Anna"), listOf("Ben")), bens.map { it.payers })
        assertEquals(listOf(cents(1000), null, cents(1000)), bens.map { it.own })
        assertEquals(emptyList(), entries(dora))
    }
}
