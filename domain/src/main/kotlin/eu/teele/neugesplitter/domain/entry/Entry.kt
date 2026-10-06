package eu.teele.neugesplitter.domain.entry

import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.AttachmentName
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId

/**
 * What an entry does to the balances (format-spec 4.2).
 *
 * An `income` is the same arithmetic with the sign reversed, and a `transfer` is an expense whose
 * beneficiary is a person rather than a purchase (event-model 3).
 */
enum class Kind {
    EXPENSE,
    INCOME,
    TRANSFER,
}

/** One entry of a group's entry list (format-spec 4.2). */
data class Entry(
    val id: EntryId,
    val kind: Kind,
    val description: String,
    val date: EntryDate,
    val amount: Amount,
    val payers: Side,
    val beneficiaries: Side,
    val note: String? = null,
    val attachments: List<AttachmentName>? = null,
) {

    /**
     * What each member paid less what they owed, negated for an `income` (format-spec 7.1).
     *
     * @throws eu.teele.neugesplitter.domain.Malformed if either side cannot be split.
     */
    fun contributions(): Map<MemberId, Amount> {
        val totals = mutableMapOf<MemberId, Amount>()
        payers.parts(amount).forEach { (member, paid) ->
            totals[member] = (totals[member] ?: Amount.ZERO) + paid
        }
        beneficiaries.parts(amount).forEach { (member, owed) ->
            totals[member] = (totals[member] ?: Amount.ZERO) - owed
        }
        return if (kind == Kind.INCOME) totals.mapValues { -it.value } else totals
    }
}
