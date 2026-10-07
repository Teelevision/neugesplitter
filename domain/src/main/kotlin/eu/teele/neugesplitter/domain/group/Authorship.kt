package eu.teele.neugesplitter.domain.group

import eu.teele.neugesplitter.domain.value.MemberId

/** Who created an entry, and whether it was changed after that (HI-4). */
data class Authorship(val by: MemberId, val edited: Boolean = false)
