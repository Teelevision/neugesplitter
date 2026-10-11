package eu.teele.neugesplitter.format.splitwise

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import java.io.InputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A Splitwise export in more than one currency, which a group, having none, cannot hold (AM-1). */
class MixedCurrencies : Exception("a Splitwise export in more than one currency")

/**
 * One row of a Splitwise export: an expense or a payment.
 *
 * @property nets what each member paid less what they owed, in the order of
 *   [SplitwiseExport.members].
 */
data class SplitwiseRow(
    val date: EntryDate,
    val description: String,
    val category: String,
    val cost: Amount,
    val nets: List<Amount>,
)

/**
 * A group as Splitwise exports it to CSV (DA-5).
 *
 * The file has five columns — date, description, category, cost, currency — and then one per
 * member, holding that member's net for the row. The headers are in the exporting user's language,
 * so columns are known by position. A last row with no cost holds each member's total, which is not
 * an entry but a check on the others.
 *
 * Only the nets are exact; who paid and how it was split is recovered from them and the cost, as
 * [entries] describes.
 *
 * @property totals each member's total as Splitwise shows it; null if the file has no total row.
 */
class SplitwiseExport(
    val members: List<String>,
    val rows: List<SplitwiseRow>,
    val totals: List<Amount>?,
    /** One unit of the finest decimal place in the file. */
    private val step: Amount,
) {

    /**
     * One entry for each row that moves money, dated as the row is, with [members] as the
     * identifiers of [SplitwiseExport.members] in order. Categories are dropped.
     * - A payment — a row in a payment category with one member up and another down by the same
     *   amount — is a transfer from the one up to the one down.
     * - A row with one member up, who then paid the whole cost, is an expense paid by that member.
     *   Each other member owes what they are down, and the payer the rest of the cost. If what each
     *   owes is the same to within [step], it is split equally; otherwise in absolute amounts.
     * - Any other row is split in absolute amounts: paid by each member up as much as they are up,
     *   for each member down as much as they are down. The cost is not recovered then.
     *
     * A row whose nets do not add up to zero is out by its difference whichever way it goes; the
     * check against [totals] shows it.
     */
    fun entries(members: List<MemberId>, ids: () -> EntryId): List<Entry> {
        require(members.size == this.members.size) { "one identifier per member" }
        return rows.mapNotNull { row ->
            split(row, members)?.let { split ->
                Entry(
                    id = ids(),
                    kind = split.kind,
                    description = row.description.ifBlank { row.category },
                    date = row.date,
                    amount = split.amount,
                    payers = split.payers,
                    beneficiaries = split.beneficiaries,
                )
            }
        }
    }

    /**
     * The largest difference between a member's balance in [balances] and their total in the file,
     * with [members] as in [entries]; null if the file has no total row.
     */
    fun difference(balances: Map<MemberId, Amount>, members: List<MemberId>): Amount? {
        val totals = totals ?: return null
        return members.indices.maxOfOrNull { index ->
            val difference = (balances[members[index]] ?: Amount.ZERO) - totals[index]
            if (difference.isNegative) -difference else difference
        }
    }

    /**
     * An entry [id] that brings each member's balance in [balances] to their total in the file,
     * paid by those below it and for those above, with [members] as in [entries]; null if the file
     * has no total row, nothing differs, or the totals do not add up to zero.
     */
    fun correction(
        balances: Map<MemberId, Amount>,
        members: List<MemberId>,
        id: EntryId,
        description: String,
        date: EntryDate,
    ): Entry? {
        val totals = totals ?: return null
        val differences =
            members.indices.map { totals[it] - (balances[members[it]] ?: Amount.ZERO) }
        val up = differences.indices.filter { differences[it].isPositive }
        val down = differences.indices.filter { differences[it].isNegative }
        if (up.isEmpty() || down.isEmpty() || !Amount.sum(differences).isZero) return null
        val split = paidBySeveral(differences, up, down, members) ?: return null
        return Entry(
            id,
            split.kind,
            description,
            date,
            split.amount,
            split.payers,
            split.beneficiaries,
        )
    }

    private data class Split(
        val kind: Kind,
        val amount: Amount,
        val payers: Side,
        val beneficiaries: Side,
    )

    /** How [row] divides between [ids]; null if it moves no money. */
    private fun split(row: SplitwiseRow, ids: List<MemberId>): Split? {
        val nets = row.nets
        val up = nets.indices.filter { nets[it].isPositive }
        val down = nets.indices.filter { nets[it].isNegative }
        if (up.isEmpty() || down.isEmpty()) return null
        if (
            row.category.lowercase(Locale.ROOT) in PAYMENTS &&
                up.size == 1 &&
                down.size == 1 &&
                nets[up[0]] == -nets[down[0]]
        ) {
            return Split(
                Kind.TRANSFER,
                nets[up[0]],
                Side.Equal(listOf(ids[up[0]])),
                Side.Equal(listOf(ids[down[0]])),
            )
        }
        if (up.size == 1 && row.cost.isPositive) {
            paidByOne(row, up.single(), ids)?.let {
                return it
            }
        }
        return paidBySeveral(nets, up, down, ids)
    }

    private fun paidByOne(row: SplitwiseRow, payer: Int, ids: List<MemberId>): Split? {
        val owed =
            row.nets.mapIndexed { index, net -> if (index == payer) row.cost - net else -net }
        if (owed[payer].isNegative) return null
        val owing = owed.indices.filter { owed[it].isPositive }
        if (owing.isEmpty()) return null
        val payers = Side.Equal(listOf(ids[payer]))
        if (owing.maxOf { owed[it] } - owing.minOf { owed[it] } <= step) {
            return Split(Kind.EXPENSE, row.cost, payers, Side.Equal(owing.map(ids::get)))
        }
        val own = row.cost - Amount.sum(owing.filter { it != payer }.map(owed::get))
        if (own.isNegative) return null
        val parts = owed.toMutableList().also { it[payer] = own }
        val beneficiaries = parts.indices.filter { parts[it].isPositive }
        return Split(
            Kind.EXPENSE,
            row.cost,
            payers,
            Side.Absolute(beneficiaries.associate { ids[it] to parts[it] }),
        )
    }

    private fun paidBySeveral(
        nets: List<Amount>,
        up: List<Int>,
        down: List<Int>,
        ids: List<MemberId>,
    ): Split? {
        val amount = Amount.sum(up.map(nets::get))
        val owed = down.associateWithTo(mutableMapOf()) { -nets[it] }
        val gap = amount - Amount.sum(owed.values)
        if (!gap.isZero) {
            val largest = down.maxBy { owed.getValue(it) }
            val adjusted = owed.getValue(largest) + gap
            if (!adjusted.isPositive) return null
            owed[largest] = adjusted
        }
        return Split(
            Kind.EXPENSE,
            amount,
            Side.Absolute(up.associate { ids[it] to nets[it] }),
            Side.Absolute(down.associate { ids[it] to owed.getValue(it) }),
        )
    }

    companion object {
        /** Larger than any export of a group anyone keeps by hand. */
        const val SIZE: Int = 16 * 1024 * 1024

        private const val DATE = 0
        private const val DESCRIPTION = 1
        private const val CATEGORY = 2
        private const val COST = 3
        private const val CURRENCY = 4
        private const val FIXED = 5

        /** The payment category as Splitwise names it in the languages known, in lower case. */
        private val PAYMENTS = setOf("payment", "zahlung")

        private val NUMBER = Regex("""-?[0-9]+(\.[0-9]+)?""")

        /**
         * The export in [input]: UTF-8 with or without a byte order mark, comma-separated and
         * quoted as RFC 4180 has it, with any line endings. Blank lines are skipped.
         *
         * @throws Malformed if it is not a Splitwise export. The message never quotes the content.
         * @throws MixedCurrencies if its rows are in more than one currency.
         */
        fun read(input: InputStream): SplitwiseExport {
            val bytes = input.readNBytes(SIZE + 1)
            if (bytes.size > SIZE) throw Malformed("a Splitwise export is over $SIZE bytes")
            val records = records(decoded(bytes).removePrefix("\uFEFF"))
            val header = records.firstOrNull() ?: throw Malformed("a Splitwise export is empty")
            if (header.size <= FIXED) throw Malformed("a Splitwise export has no member columns")
            val members = header.drop(FIXED).map { it.trim() }
            if (members.any { it.isEmpty() }) throw Malformed("a member column has no name")
            val numbers = Numbers()
            val currencies = mutableSetOf<String>()
            val rows = mutableListOf<SplitwiseRow>()
            var totals: List<Amount>? = null
            for ((index, record) in records.drop(1).withIndex()) {
                if (record.size != header.size) {
                    throw Malformed("row $index has ${record.size} fields, not ${header.size}")
                }
                record[CURRENCY].trim().takeIf { it.isNotEmpty() }?.let(currencies::add)
                val nets = record.drop(FIXED).map { numbers.amount(it, index) }
                if (record[COST].isBlank()) {
                    if (totals != null) throw Malformed("a Splitwise export has two total rows")
                    totals = nets
                    continue
                }
                rows +=
                    SplitwiseRow(
                        date = date(record[DATE], index),
                        description = record[DESCRIPTION].trim(),
                        category = record[CATEGORY].trim(),
                        cost = numbers.amount(record[COST], index),
                        nets = nets,
                    )
            }
            if (currencies.size > 1) throw MixedCurrencies()
            val step =
                Amount.ofUnits(
                    BigDecimal.ONE.movePointRight(Amount.UNIT_DIGITS - numbers.decimals)
                        .longValueExact()
                )
            return SplitwiseExport(members, rows, totals, step)
        }

        private fun decoded(bytes: ByteArray): String =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw Malformed("a Splitwise export is not UTF-8")
            }

        /** The records of [text], each a list of its fields, blank ones left out. */
        private fun records(text: String): List<List<String>> {
            val records = mutableListOf<List<String>>()
            var fields = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var at = 0
            while (at < text.length) {
                val c = text[at]
                when {
                    quoted && c == '"' && text.getOrNull(at + 1) == '"' -> {
                        field.append('"')
                        at++
                    }
                    quoted && c == '"' -> quoted = false
                    quoted -> field.append(c)
                    c == '"' -> quoted = true
                    c == ',' -> {
                        fields += field.toString()
                        field.clear()
                    }
                    c == '\r' || c == '\n' -> {
                        if (c == '\r' && text.getOrNull(at + 1) == '\n') at++
                        fields += field.toString()
                        field.clear()
                        records += fields
                        fields = mutableListOf()
                    }
                    else -> field.append(c)
                }
                at++
            }
            if (quoted) throw Malformed("a quoted field in a Splitwise export is not closed")
            if (field.isNotEmpty() || fields.isNotEmpty()) {
                fields += field.toString()
                records += fields
            }
            return records.filterNot { record -> record.all { it.isBlank() } }
        }

        private fun date(text: String, row: Int): EntryDate =
            try {
                EntryDate.parse(text.trim())
            } catch (_: RuntimeException) {
                throw Malformed("row $row has no date")
            }
    }

    /** Reads amounts, and keeps the most decimal places any of them has. */
    private class Numbers {
        var decimals = 0
            private set

        /** [text] as an amount; zero if it is blank. */
        fun amount(text: String, row: Int): Amount {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return Amount.ZERO
            if (!NUMBER.matches(trimmed)) throw Malformed("row $row has an amount that is not one")
            val decimal = BigDecimal(trimmed)
            if (decimal.scale() > Amount.UNIT_DIGITS) {
                throw Malformed("row $row has an amount finer than 10⁻⁸")
            }
            decimals = maxOf(decimals, decimal.scale())
            return try {
                Amount.ofUnits(decimal.movePointRight(Amount.UNIT_DIGITS).longValueExact())
            } catch (_: ArithmeticException) {
                throw Malformed("row $row has an amount out of range")
            } catch (_: IllegalArgumentException) {
                throw Malformed("row $row has an amount out of range")
            }
        }
    }
}
