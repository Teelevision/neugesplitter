package eu.teele.neugesplitter.domain.value

/** A group's colour, `#rrggbb` (format-spec 4.2). */
@JvmInline
value class Colour private constructor(val text: String) {

    override fun toString(): String = text

    companion object {
        private val WIRE = Regex("""#[0-9a-fA-F]{6}""")

        /** Case is kept as written: nothing in the format normalises it. */
        fun parse(text: String): Colour {
            require(WIRE.matches(text)) { "not a colour: '$text'" }
            return Colour(text)
        }
    }
}

/** The hex SHA-256 of an attachment's plaintext bytes, which is also its key (format-spec 9). */
@JvmInline
value class AttachmentName private constructor(val hex: String) : Comparable<AttachmentName> {

    override fun compareTo(other: AttachmentName): Int = hex.compareTo(other.hex)

    override fun toString(): String = hex

    companion object {
        private val WIRE = Regex("""[0-9a-f]{64}""")

        fun parse(hex: String): AttachmentName {
            require(WIRE.matches(hex)) { "not an attachment name: '$hex'" }
            return AttachmentName(hex)
        }
    }
}
