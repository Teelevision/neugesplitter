package eu.teele.neugesplitter.domain.balance

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId

/** [from] pays [to] the positive [amount], as one step of settling a group (BA-3). */
data class Transfer(val from: MemberId, val to: MemberId, val amount: Amount)
