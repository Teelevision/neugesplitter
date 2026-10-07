package eu.teele.neugesplitter.storage

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.Scale

/**
 * A group as the group list shows it (ui-design 3).
 *
 * @property entries how many entries count towards its balances, pending occurrences not included.
 * @property own the settled balance of the member this installation acts as (BA-7), or null if it
 *   acts as none.
 */
data class ListedGroup(
    val id: GroupId,
    val name: String,
    val emoji: String?,
    val colour: Colour?,
    val scale: Scale,
    val entries: Long,
    val own: Amount?,
)
