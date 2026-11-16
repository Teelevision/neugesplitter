package eu.teele.neugesplitter.domain.value

import java.time.LocalDate

/**
 * A date in the proleptic Gregorian calendar, with no time and no zone (format-spec 3.2).
 *
 * Entry dates and recurrence due dates are dates. Event timestamps are instants, are a record only,
 * and are not part of the domain: replay depends on object number order and never on a clock
 * (TR-19).
 */
@JvmInline
value class EntryDate private constructor(val value: LocalDate) : Comparable<EntryDate> {

    override fun compareTo(other: EntryDate): Int = value.compareTo(other.value)

    /** The canonical wire form, `YYYY-MM-DD`. */
    override fun toString(): String = value.toString()

    companion object {
        private val WIRE = Regex("""\d{4}-\d{2}-\d{2}""")

        fun of(value: LocalDate): EntryDate = EntryDate(value)

        fun parse(text: String): EntryDate {
            require(WIRE.matches(text)) { "not a canonical date: '$text'" }
            // Read by hand: the platform's formatter takes long, thousands of times at start-up.
            return EntryDate(
                LocalDate.of(
                    text.substring(0, 4).toInt(),
                    text.substring(5, 7).toInt(),
                    text.substring(8, 10).toInt(),
                )
            )
        }
    }
}
