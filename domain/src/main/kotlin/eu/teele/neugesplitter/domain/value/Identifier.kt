package eu.teele.neugesplitter.domain.value

/**
 * The shared form of every identifier in the format: 128 bits as 32 lowercase hex characters, with
 * no type prefix (format-spec 3.1).
 *
 * The types below are distinct so that a member identifier cannot be passed where an entry
 * identifier is expected. Nothing in the format distinguishes them; the compiler does.
 */
private const val LENGTH = 32

private fun requireIdentifier(hex: String, what: String): String {
    require(hex.length == LENGTH) { "$what must be $LENGTH hex characters, was ${hex.length}" }
    require(hex.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$what must be lowercase hex, was '$hex'"
    }
    return hex
}

@JvmInline
value class GroupId private constructor(val hex: String) : Comparable<GroupId> {
    override fun compareTo(other: GroupId): Int = hex.compareTo(other.hex)

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): GroupId = GroupId(requireIdentifier(hex, "group id"))
    }
}

@JvmInline
value class MemberId private constructor(val hex: String) : Comparable<MemberId> {
    override fun compareTo(other: MemberId): Int = hex.compareTo(other.hex)

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): MemberId = MemberId(requireIdentifier(hex, "member id"))
    }
}

@JvmInline
value class EntryId private constructor(val hex: String) : Comparable<EntryId> {
    override fun compareTo(other: EntryId): Int = hex.compareTo(other.hex)

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): EntryId = EntryId(requireIdentifier(hex, "entry id"))
    }
}
