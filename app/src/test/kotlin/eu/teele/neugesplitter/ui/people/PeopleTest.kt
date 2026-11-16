package eu.teele.neugesplitter.ui.people

import eu.teele.neugesplitter.domain.balance.Transfer
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.ListedMember
import eu.teele.neugesplitter.storage.People
import kotlin.test.Test
import kotlin.test.assertEquals

class PeopleTest {

    private val anna = MemberId.parse("%032x".format(1))
    private val ben = MemberId.parse("%032x".format(2))
    private val clara = MemberId.parse("%032x".format(3))
    private val dora = MemberId.parse("%032x".format(4))

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    /** Anna paid 30.00 for herself, Ben and Clara; Dora joined and left without taking part. */
    private val group =
        People(
            name = "Trip",
            emoji = "⛺",
            scale = Scale.of(2),
            members =
                listOf(
                    ListedMember(anna, "Anna", false, cents(2000), true),
                    ListedMember(ben, "Ben", false, cents(-1000), true),
                    ListedMember(clara, "Clara", false, cents(-1000), true),
                    ListedMember(dora, "Dora", false, cents(0), false),
                ),
        )

    @Test
    fun `members are listed in the order they were added, with the transfers that settle them (BA-1, BA-3)`() {
        val state = people(group, ben)
        assertEquals("⛺ Trip", state.title)
        assertEquals(
            listOf(
                anna to cents(2000),
                ben to cents(-1000),
                clara to cents(-1000),
                dora to cents(0),
            ),
            state.people.map { it.id to it.balance },
        )
        assertEquals(listOf(ben), state.people.filter { it.me }.map { it.id })
        assertEquals(
            listOf(Transfer(ben, anna, cents(1000)), Transfer(clara, anna, cents(1000))),
            state.transfers,
        )
    }

    @Test
    fun `a removed member is listed while they appear in an entry or have a balance (GR-9)`() {
        val removed =
            group.copy(
                members =
                    group.members.map {
                        when (it.id) {
                            clara -> it.copy(removed = true)
                            dora -> it.copy(removed = true)
                            else -> it
                        }
                    }
            )
        val state = people(removed, anna)
        assertEquals(listOf(anna, ben, clara), state.people.map { it.id })
        assertEquals(listOf(clara), state.people.filter { it.removed }.map { it.id })

        val owing =
            removed.copy(
                members =
                    removed.members.map {
                        when (it.id) {
                            dora -> it.copy(balance = cents(5))
                            anna -> it.copy(balance = cents(1995))
                            else -> it
                        }
                    }
            )
        assertEquals(listOf(anna, ben, clara, dora), people(owing, anna).people.map { it.id })
    }
}
