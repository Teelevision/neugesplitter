package eu.teele.neugesplitter.format

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.format.splitwise.MixedCurrencies
import eu.teele.neugesplitter.format.splitwise.SplitwiseExport
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Every file here is made up; none is from a real export. */
class SplitwiseExportTest {

    private val anna = MemberId.parse("a1".repeat(16))
    private val ben = MemberId.parse("b2".repeat(16))
    private val cleo = MemberId.parse("c3".repeat(16))
    private val members = listOf(anna, ben, cleo)

    private val header = "Date,Description,Category,Cost,Currency,Anna,Ben,Cleo"

    private fun money(text: String): Amount =
        Amount.ofUnits(BigDecimal(text).movePointRight(Amount.UNIT_DIGITS).longValueExact())

    private fun read(vararg rows: String, prefix: String = "", ending: String = "\n") =
        SplitwiseExport.read(
            (prefix + (listOf(header) + rows).joinToString(ending)).byteInputStream()
        )

    private fun entries(export: SplitwiseExport): List<Entry> {
        var next = 0
        return export.entries(members) { EntryId.parse("%032x".format(++next)) }
    }

    private fun single(row: String): Entry = entries(read(row)).single()

    private fun balances(entries: List<Entry>): Map<MemberId, Amount> =
        entries
            .flatMap { it.contributions().entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { Amount.sum(it.value) }

    @Test
    fun `a row paid by one and shared alike is split equally`() {
        val entry = single("2024-01-05,Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00")

        assertEquals(Kind.EXPENSE, entry.kind)
        assertEquals("Groceries", entry.description)
        assertEquals(EntryDate.parse("2024-01-05"), entry.date)
        assertEquals(money("30"), entry.amount)
        assertEquals(Side.Equal(listOf(anna)), entry.payers)
        assertEquals(Side.Equal(listOf(anna, ben, cleo)), entry.beneficiaries)
    }

    @Test
    fun `a payer who owes nothing is not among those it is for`() {
        val entry = single("2024-01-05,Taxi,Transport,20.00,EUR,20.00,-10.00,-10.00")

        assertEquals(Side.Equal(listOf(ben, cleo)), entry.beneficiaries)
    }

    @Test
    fun `an odd cent still counts as equal`() {
        val three = single("2024-01-05,Pizza,Dining out,10.00,EUR,6.67,-3.33,-3.34")
        val two = single("2024-01-05,Gum,Groceries,1.55,EUR,0.78,-0.77,0.00")

        assertEquals(Side.Equal(listOf(anna, ben, cleo)), three.beneficiaries)
        assertEquals(Side.Equal(listOf(anna, ben)), two.beneficiaries)
        assertEquals(money("1.55"), two.amount)
    }

    @Test
    fun `a row shared unevenly is split in absolute amounts`() {
        val entry = single("2024-01-06,Dinner,Dining out,50.00,EUR,35.00,-20.00,-15.00")

        assertEquals(Side.Equal(listOf(anna)), entry.payers)
        assertEquals(
            Side.Absolute(mapOf(anna to money("15"), ben to money("20"), cleo to money("15"))),
            entry.beneficiaries,
        )
    }

    @Test
    fun `the payer's own part is what the cost leaves`() {
        val entry = single("2024-01-06,Hotel,Lodging,100.00,EUR,45.00,-45.00,0.00")

        assertEquals(money("100"), entry.amount)
        assertEquals(
            Side.Absolute(mapOf(anna to money("55"), ben to money("45"))),
            entry.beneficiaries,
        )
        assertEquals(mapOf(anna to money("45"), ben to money("-45")), entry.contributions())
    }

    @Test
    fun `a row whose nets do not add up is out at the payer`() {
        val entry = single("2024-01-06,Dinner,Dining out,50.00,EUR,30.00,-15.00,-5.00")

        assertEquals(
            Side.Absolute(mapOf(anna to money("30"), ben to money("15"), cleo to money("5"))),
            entry.beneficiaries,
        )
        assertEquals(
            mapOf(anna to money("20"), ben to money("-15"), cleo to money("-5")),
            entry.contributions(),
        )
    }

    @Test
    fun `a payment is a transfer from the one up to the one down`() {
        val entry = single("2024-01-07,Ben B. paid Anna A.,Payment,10.00,EUR,-10.00,10.00,0.00")

        assertEquals(Kind.TRANSFER, entry.kind)
        assertEquals(money("10"), entry.amount)
        assertEquals(Side.Equal(listOf(ben)), entry.payers)
        assertEquals(Side.Equal(listOf(anna)), entry.beneficiaries)
    }

    @Test
    fun `the German payment category is a payment too`() {
        assertEquals(
            Kind.TRANSFER,
            single("2024-01-07,Ben zahlte Anna,Zahlung,10.00,EUR,-10.00,10.00,0.00").kind,
        )
    }

    @Test
    fun `a row like a payment in another category is an expense`() {
        val entry = single("2024-01-07,Cinema,Entertainment,10.00,EUR,10.00,-10.00,0.00")

        assertEquals(Kind.EXPENSE, entry.kind)
        assertEquals(Side.Equal(listOf(ben)), entry.beneficiaries)
    }

    @Test
    fun `a row paid by several is split in absolute amounts on both sides`() {
        val entry = single("2024-01-08,Trip,General,90.00,EUR,30.00,30.00,-60.00")

        assertEquals(money("60"), entry.amount)
        assertEquals(Side.Absolute(mapOf(anna to money("30"), ben to money("30"))), entry.payers)
        assertEquals(Side.Absolute(mapOf(cleo to money("60"))), entry.beneficiaries)
    }

    @Test
    fun `every row keeps each member's net when the nets add up`() {
        val rows =
            arrayOf(
                "2024-01-05,Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00",
                "2024-01-06,Dinner,Dining out,50.00,EUR,30.00,-15.00,-15.00",
                "2024-01-06,Hotel,Lodging,100.00,EUR,45.00,-45.00,0.00",
                "2024-01-07,Ben B. paid Anna A.,Payment,10.00,EUR,-10.00,10.00,0.00",
                "2024-01-08,Trip,General,90.00,EUR,30.00,30.00,-60.00",
            )

        val sums = balances(entries(read(*rows)))

        assertEquals(mapOf(anna to money("115"), ben to money("-30"), cleo to money("-85")), sums)
    }

    @Test
    fun `a row that moves no money is left out`() {
        assertEquals(
            emptyList(),
            entries(read("2024-01-05,Nothing,General,0.00,EUR,0.00,0.00,0.00")),
        )
    }

    @Test
    fun `the total row is a check and not an entry`() {
        val export =
            read(
                "2024-01-05,Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00",
                "2024-01-08,Total balance, , ,EUR,20.00,-10.00,-10.00",
            )
        val entries = entries(export)

        assertEquals(1, entries.size)
        assertEquals(listOf(money("20"), money("-10"), money("-10")), export.totals)
        assertEquals(Amount.ZERO, export.difference(balances(entries), members))
        assertEquals(
            money("0.01"),
            export.difference(
                mapOf(anna to money("20.01"), ben to money("-10"), cleo to money("-10")),
                members,
            ),
        )
    }

    @Test
    fun `a correction brings every balance to its total`() {
        val export =
            read(
                "2024-01-05,Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00",
                "2024-01-08,Total balance, , ,EUR,20.05,-10.00,-10.05",
            )
        val entries = entries(export)
        val id = EntryId.parse("ff".repeat(16))
        val date = EntryDate.parse("2024-01-09")

        val correction =
            assertNotNull(export.correction(balances(entries), members, id, "Correction", date))

        assertEquals(id, correction.id)
        assertEquals("Correction", correction.description)
        assertEquals(date, correction.date)
        assertEquals(Amount.ZERO, export.difference(balances(entries + correction), members))
    }

    @Test
    fun `there is no correction when nothing differs or the totals do not add up`() {
        val id = EntryId.parse("ff".repeat(16))
        val date = EntryDate.parse("2024-01-09")
        val row = "2024-01-05,Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00"
        val same = read(row, "2024-01-08,Total balance, , ,EUR,20.00,-10.00,-10.00")
        val uneven = read(row, "2024-01-08,Total balance, , ,EUR,20.05,-10.00,-10.00")

        assertNull(same.correction(balances(entries(same)), members, id, "Correction", date))
        assertNull(uneven.correction(balances(entries(uneven)), members, id, "Correction", date))
    }

    @Test
    fun `without a total row there is nothing to check`() {
        assertNull(
            read("2024-01-05,Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00")
                .difference(emptyMap(), members)
        )
    }

    @Test
    fun `a byte order mark, CRLF, quoted fields and blank lines are read`() {
        val export =
            read(
                "",
                "2024-01-05,\"Rice, beans and \"\"tea\"\"\",Groceries,30.00,EUR,20.00,-10.00,-10.00",
                "",
                prefix = "\uFEFF",
                ending = "\r\n",
            )

        assertEquals(listOf("Anna", "Ben", "Cleo"), export.members)
        assertEquals("Rice, beans and \"tea\"", export.rows.single().description)
    }

    @Test
    fun `members are known by position, whatever the headers say`() {
        val export =
            SplitwiseExport.read(
                ("Datum,Beschreibung,Kategorie,Kosten,Währung,Anna,Ben,Cleo\n" +
                        "2024-01-05,Einkauf,Lebensmittel,30.00,EUR,20.00,-10.00,-10.00")
                    .byteInputStream()
            )

        assertEquals(listOf("Anna", "Ben", "Cleo"), export.members)
        assertEquals(1, export.rows.size)
    }

    @Test
    fun `a file in more than one currency is refused`() {
        assertFailsWith<MixedCurrencies> {
            read(
                "2024-01-05,Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00",
                "2024-01-06,Snacks,Groceries,30.00,USD,20.00,-10.00,-10.00",
            )
        }
    }

    @Test
    fun `a file that is not a Splitwise export is malformed`() {
        val broken =
            listOf(
                "",
                "Date,Description,Category,Cost,Currency",
                "$header\n2024-01-05,Groceries,Groceries,30.00,EUR,20.00,-10.00",
                "$header\n2024-01-05,\"Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00",
                "$header\nyesterday,Groceries,Groceries,30.00,EUR,20.00,-10.00,-10.00",
                "$header\n2024-01-05,Groceries,Groceries,thirty,EUR,20.00,-10.00,-10.00",
                "Date,Description,Category,Cost,Currency,Anna, \n",
            )
        for (text in broken) {
            assertFailsWith<Malformed>(text) { SplitwiseExport.read(text.byteInputStream()) }
        }
        assertFailsWith<Malformed> {
            SplitwiseExport.read(byteArrayOf(0xff.toByte(), 0xfe.toByte()).inputStream())
        }
    }
}
