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
 * @property unseen whether it holds changes from another installation not seen yet (HI-5).
 * @property halted whether its sync has halted (SY-15).
 */
data class ListedGroup(
    val id: GroupId,
    val name: String,
    val emoji: String?,
    val colour: Colour?,
    val scale: Scale,
    val entries: Long,
    val own: Amount?,
    val unseen: Boolean,
    val halted: Boolean,
)
