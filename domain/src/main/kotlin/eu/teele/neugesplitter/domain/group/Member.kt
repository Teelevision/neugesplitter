package eu.teele.neugesplitter.domain.group

import eu.teele.neugesplitter.domain.value.MemberId

/**
 * A member of a group (format-spec 4.2).
 *
 * Which member an installation acts as is local to that installation and has no event, so nothing
 * here says anything about devices (ID-3, event-model 5).
 */
data class Member(val id: MemberId, val name: String)
