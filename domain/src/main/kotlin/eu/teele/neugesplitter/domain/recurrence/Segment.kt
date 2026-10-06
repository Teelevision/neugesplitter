package eu.teele.neugesplitter.domain.recurrence

import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.AttachmentName
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId

/** Whether a due date becomes an entry by itself or waits to be confirmed (RE-3). */
enum class Mode {
    AUTOMATIC,
    SUGGESTED,
}

/** An entry without `id` or `date` (format-spec 4.2). */
data class Template(
    val kind: Kind,
    val description: String,
    val amount: Amount,
    val payers: Side,
    val beneficiaries: Side,
    val note: String? = null,
    val attachments: List<AttachmentName>? = null,
) {
    fun on(id: EntryId, date: EntryDate): Entry =
        Entry(id, kind, description, date, amount, payers, beneficiaries, note, attachments)
}

/** What a series does from a date onward (format-spec 4.2, event-model 6). */
data class Segment(val interval: Interval, val mode: Mode, val template: Template)
