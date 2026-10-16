package eu.teele.neugesplitter.ui.overview

import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.ListedEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EntryFilterTest {

    private fun id(n: Int) = "%032x".format(n)

    private val anna = MemberId.parse(id(1))
    private val ben = MemberId.parse(id(2))

    private fun listed(
        n: Int,
        description: String,
        euros: Long,
        date: String,
        kind: Kind = Kind.EXPENSE,
        payers: Set<MemberId> = setOf(anna),
        beneficiaries: Set<MemberId> = setOf(anna, ben),
    ) =
        ListedEntry(
            EntryId.parse(id(10 + n)),
            kind,
            description,
            EntryDate.parse(date),
            Amount.ofUnits(euros * 100_000_000L),
            payers = emptyList(),
            own = null,
            repeats = false,
            edited = false,
            unseen = false,
            payerIds = payers,
            beneficiaryIds = beneficiaries,
        )

    private val dinner = listed(1, "Dinner", 30, "2026-10-07")
    private val taxi = listed(2, "Taxi home", 12, "2026-10-06", payers = setOf(ben))
    private val refund =
        listed(3, "Tax refund", 100, "2026-09-30", Kind.INCOME, beneficiaries = setOf(ben))
    private val all = listOf(dinner, taxi, refund)

    private fun matching(filter: EntryFilter) =
        filter.matching(all, Scale.DEFAULT).map { it.description }

    @Test
    fun `no filter lists everything (EN-12)`() {
        assertFalse(EntryFilter(text = "  ").active)
        assertEquals(all.map { it.description }, matching(EntryFilter()))
    }

    @Test
    fun `the description matches by a part of it, in any case (EN-12)`() {
        assertEquals(listOf("Taxi home", "Tax refund"), matching(EntryFilter(text = " tax")))
    }

    @Test
    fun `either bound of the amount counts, as typed (EN-12)`() {
        assertEquals(listOf("Dinner", "Taxi home"), matching(EntryFilter(most = "30,00")))
        assertEquals(listOf("Dinner", "Tax refund"), matching(EntryFilter(least = "30")))
        assertEquals(listOf("Dinner"), matching(EntryFilter(least = "12.01", most = "99")))
        assertEquals(all.map { it.description }, matching(EntryFilter(least = "a lot")))
    }

    @Test
    fun `the kind, the payers, the beneficiaries and the dates narrow it further (EN-12)`() {
        assertEquals(listOf("Tax refund"), matching(EntryFilter(kind = Kind.INCOME)))
        assertEquals(listOf("Taxi home"), matching(EntryFilter(payers = setOf(ben))))
        assertEquals(
            listOf("Dinner", "Taxi home"),
            matching(EntryFilter(beneficiaries = setOf(anna, ben))),
        )
        assertEquals(
            listOf("Taxi home", "Tax refund"),
            matching(EntryFilter(until = EntryDate.parse("2026-10-06"))),
        )
        assertEquals(
            listOf("Dinner", "Taxi home"),
            matching(EntryFilter(from = EntryDate.parse("2026-10-06"))),
        )
    }
}
