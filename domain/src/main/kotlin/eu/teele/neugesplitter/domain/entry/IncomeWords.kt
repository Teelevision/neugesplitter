package eu.teele.neugesplitter.domain.entry

/**
 * Whether a description reads like money coming in rather than going out, in English or German.
 *
 * This only proposes candidates; a person confirms every conversion. Refunds and deposits returned
 * are not income. Stems match anywhere, so German compounds such as "Monatsgehalt" are found; words
 * match whole, so "Rente" does not catch "Rentenversicherung".
 */
object IncomeWords {

    private val stems =
        listOf(
            "gehalt",
            "lohnzahlung",
            "auszahlung",
            "verkauf",
            "einnahme",
            "kindergeld",
            "elterngeld",
            "wohngeld",
            "arbeitslosengeld",
            "bürgergeld",
            "krankengeld",
            "stipendium",
            "honorar",
            "dividende",
            "lottogewinn",
            "untermiete",
            "weihnachtsgeld",
            "urlaubsgeld",
            "taschengeld",
            "trinkgeld erhalten",
            "salary",
            "paycheck",
            "paycheque",
            "payroll",
            "dividend",
            "scholarship",
            "winnings",
            "payout",
        )

    private val words =
        setOf(
            "lohn",
            "rente",
            "pension",
            "bafög",
            "zinsen",
            "verkauft",
            "bonus",
            "provision",
            "sold",
            "sell",
            "selling",
            "wage",
            "wages",
            "income",
            "interest",
            "tips",
            "prize",
            "allowance",
        )

    private val separator = Regex("[^\\p{L}\\p{N}]+")

    fun match(description: String): Boolean {
        val text = description.lowercase()
        return stems.any { it in text } || text.split(separator).any { it in words }
    }
}
