package eu.teele.neugesplitter.domain.entry

/**
 * Whether a description reads like money coming in rather than going out, in English or German.
 *
 * This only proposes candidates; a person confirms every conversion. Stems match anywhere, so
 * German compounds such as "Steuererstattung" are found; words match whole, so "Rente" does not
 * catch "Rentenversicherung".
 */
object IncomeWords {

    private val stems =
        listOf(
            "gehalt",
            "lohnzahlung",
            "erstattung",
            "rückzahlung",
            "gutschrift",
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
            "cashback",
            "salary",
            "paycheck",
            "paycheque",
            "payroll",
            "refund",
            "reimburse",
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
            "pfand",
            "kaution",
            "zurück",
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
            "rebate",
            "allowance",
            "returned",
        )

    private val separator = Regex("[^\\p{L}\\p{N}]+")

    fun match(description: String): Boolean {
        val text = description.lowercase()
        return stems.any { it in text } || text.split(separator).any { it in words }
    }
}
