package eu.teele.neugesplitter.ui.history

import android.content.res.Resources
import android.icu.text.ListFormatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.event.Change
import eu.teele.neugesplitter.domain.event.EntryChanges
import eu.teele.neugesplitter.domain.history.Happened
import eu.teele.neugesplitter.domain.history.Happening
import eu.teele.neugesplitter.domain.history.Revision
import eu.teele.neugesplitter.domain.history.SeriesChange
import eu.teele.neugesplitter.domain.history.Setting
import eu.teele.neugesplitter.domain.history.Settings
import eu.teele.neugesplitter.domain.history.TodoChange
import eu.teele.neugesplitter.domain.history.TodosChange
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.recurrence.Segment
import eu.teele.neugesplitter.domain.todo.Todo
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.ui.UnseenDot
import eu.teele.neugesplitter.ui.editor.recurrenceText
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.sideLabels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/** What no value is, on either side of a change. */
private const val NONE = "—"

/** The day [at] fell on here, which a history files a step under (ui-design 4). */
fun day(at: Instant): EntryDate = EntryDate.of(at.atZone(ZoneId.systemDefault()).toLocalDate())

/** "Oct 5, 2026", as [locale] writes it: short enough to share a line. */
fun mediumDate(date: EntryDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date.value)

private fun clock(at: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(at)

/** One step of a history: an [icon] for its kind, who did what, and what changed. */
data class Told(@DrawableRes val icon: Int, val headline: String, val detail: String)

/**
 * A step of a history the size of an entry row however much it says (ui-design 4): [told] on two
 * lines, each cut off with an ellipsis where it does not fit, and the time of day at the end.
 */
@Composable
fun HistoryRow(
    told: Told,
    at: Instant,
    locale: Locale,
    modifier: Modifier = Modifier,
    unseen: Boolean = false,
) {
    val colours = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(40.dp).clip(CircleShape).background(colours.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            // The headline says what kind of change it is.
            Icon(
                painterResource(told.icon),
                contentDescription = null,
                tint = colours.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    told.headline,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unseen) UnseenDot(Modifier.padding(start = 8.dp))
            }
            Text(
                told.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            clock(at, locale),
            style = MaterialTheme.typography.bodyMedium.merge(TABULAR),
            color = colours.onSurfaceVariant,
        )
    }
}

/** [happening] as a row or a notification tells it: who did what, and what changed (HI-2). */
fun Resources.told(
    happening: Happening,
    names: Map<MemberId, String>,
    scale: Scale,
    locale: Locale,
): Told {
    fun name(member: MemberId) = names[member] ?: member.hex.take(8)
    val by = name(happening.by)
    return when (val happened = happening.happened) {
        Happened.Created -> Told(R.drawable.ic_add, getString(R.string.happened_created, by), "")
        is Happened.Changed ->
            Told(
                R.drawable.ic_settings,
                getString(
                    when (happened.settings.singleOrNull()) {
                        Setting.NAME -> R.string.happened_name
                        Setting.DESCRIPTION -> R.string.happened_description
                        Setting.EMOJI -> R.string.happened_emoji
                        Setting.COLOUR -> R.string.happened_colour
                        Setting.DEFAULT_PARTICIPANTS -> R.string.happened_participants
                        Setting.SCALE -> R.string.happened_scale
                        null -> R.string.happened_settings
                    },
                    by,
                ),
                settingChanges(happened.settings, happened.before, happened.after, names, locale)
                    .joinToString(" · "),
            )
        is Happened.MemberAdded ->
            Told(
                R.drawable.ic_people,
                if (happened.member == happening.by) getString(R.string.happened_joined, by)
                else getString(R.string.happened_added, by, name(happened.member)),
                "",
            )
        is Happened.MemberRenamed ->
            Told(
                R.drawable.ic_people,
                getString(R.string.happened_renamed, by, happened.from, happened.to),
                "",
            )
        is Happened.MemberRemoved ->
            Told(
                R.drawable.ic_people,
                getString(R.string.happened_removed, by, name(happened.member)),
                "",
            )
        is Happened.Entry -> {
            val (icon, sentence) =
                when (happened.change) {
                    Revision.CREATED -> R.drawable.ic_add to R.string.happened_entry_created
                    Revision.EDITED -> R.drawable.ic_edit to R.string.happened_entry_edited
                    Revision.DELETED -> R.drawable.ic_delete to R.string.happened_entry_deleted
                    Revision.RESTORED -> R.drawable.ic_history to R.string.happened_entry_restored
                }
            Told(
                icon,
                getString(sentence, by, happened.description),
                revised(happened.before, happened.after, scale, names, locale),
            )
        }
        is Happened.MadeRecurring ->
            Told(
                R.drawable.ic_repeat,
                getString(R.string.happened_made_recurring, by, happened.description),
                recurrenceText(happened.segment.interval, locale),
            )
        is Happened.Recurring -> recurring(happened, by, names, scale, locale)
        is Happened.Todo -> todo(happened, by, names, locale)
        is Happened.Todos -> todos(happened, by)
    }
}

/** One todo by its description, several by their number and descriptions below. */
private fun Resources.todos(happened: Happened.Todos, by: String): Told {
    val (icon, one, several) =
        when (happened.change) {
            TodosChange.MOVED ->
                Triple(
                    R.drawable.ic_drag_indicator,
                    R.string.happened_todo_moved,
                    R.plurals.happened_todos_moved,
                )
            TodosChange.COMPLETED ->
                Triple(
                    R.drawable.ic_check,
                    R.string.happened_todo_completed,
                    R.plurals.happened_todos_completed,
                )
            TodosChange.REOPENED ->
                Triple(
                    R.drawable.ic_check_box_outline_blank,
                    R.string.happened_todo_reopened,
                    R.plurals.happened_todos_reopened,
                )
        }
    val descriptions = happened.descriptions
    return descriptions.singleOrNull()?.let { Told(icon, getString(one, by, it), "") }
        ?: Told(
            icon,
            getQuantityString(several, descriptions.size, by, descriptions.size),
            descriptions.joinToString(" · "),
        )
}

private fun Resources.todo(
    happened: Happened.Todo,
    by: String,
    names: Map<MemberId, String>,
    locale: Locale,
): Told {
    val (icon, sentence, detail) =
        when (val change = happened.change) {
            TodoChange.Created -> Triple(R.drawable.ic_add, R.string.happened_todo_created, "")
            is TodoChange.Edited ->
                Triple(
                    R.drawable.ic_edit,
                    R.string.happened_todo_edited,
                    todoChanges(change.before, change.after, names, locale),
                )
            TodoChange.Deleted -> Triple(R.drawable.ic_delete, R.string.happened_todo_deleted, "")
        }
    return Told(icon, getString(sentence, by, happened.description), detail)
}

/** One part for each field in which [after] differs from [before], the same todo. */
private fun Resources.todoChanges(
    before: Todo,
    after: Todo,
    names: Map<MemberId, String>,
    locale: Locale,
): String {
    fun due(todo: Todo) = todo.due?.let { mediumDate(it, locale) } ?: NONE
    fun assigned(todo: Todo) =
        todo.assignees
            .map { names[it] ?: it.hex.take(8) }
            .ifEmpty { null }
            ?.let { ListFormatter.getInstance(locale).format(it) } ?: NONE
    val fields =
        listOf(
            Triple(R.string.entry_description, before.description, after.description),
            Triple(R.string.todo_due, due(before), due(after)),
            Triple(R.string.todo_assignees, assigned(before), assigned(after)),
        )
    return fields
        .filter { (_, old, new) -> old != new }
        .map { (field, old, new) ->
            getString(R.string.history_field_changed, getString(field), new, old)
        }
        .joinToString(" · ")
}

private fun Resources.recurring(
    happened: Happened.Recurring,
    by: String,
    names: Map<MemberId, String>,
    scale: Scale,
    locale: Locale,
): Told {
    val what = happened.description
    fun from(date: EntryDate) = mediumDate(date, locale)
    val (sentence, detail) =
        when (val change = happened.change) {
            is SeriesChange.Started ->
                R.string.happened_series_started to
                    listOf(
                            recurrenceText(change.segment.interval, locale),
                            formatted(change.segment.template.amount, scale, locale),
                        )
                        .joinToString(" · ")
            is SeriesChange.Amended ->
                R.string.happened_series_amended to
                    (listOf(getString(R.string.history_from, from(change.from))) +
                            (change.before?.let {
                                amended(
                                    happened.series,
                                    change.from,
                                    it,
                                    change.after,
                                    scale,
                                    names,
                                    locale,
                                )
                            } ?: emptyList()))
                        .joinToString(" · ")
            is SeriesChange.Stopped -> {
                val until = change.stop.until
                if (until == null) {
                    R.string.happened_series_stopped to
                        getString(R.string.series_stop_open, from(change.stop.from))
                } else {
                    R.string.happened_series_paused to
                        getString(R.string.series_stop_span, from(change.stop.from), from(until))
                }
            }
            is SeriesChange.Continued ->
                R.string.happened_series_continued to
                    getString(R.string.history_from, from(change.span.from))
            SeriesChange.Deleted -> R.string.happened_series_deleted to ""
            is SeriesChange.Confirmed ->
                R.string.happened_occurrence_confirmed to
                    getString(R.string.suggestion_due, from(change.due))
            is SeriesChange.LeftOut ->
                R.string.happened_occurrence_left_out to
                    getString(R.string.suggestion_due, from(change.due))
            is SeriesChange.Edited ->
                R.string.happened_entry_edited to
                    revised(change.before, change.after, scale, names, locale)
        }
    return Told(R.drawable.ic_repeat, getString(sentence, by, what), detail)
}

/** What changed from [before] to [after], or what [after] is where there was nothing before. */
fun Resources.revised(
    before: Entry?,
    after: Entry,
    scale: Scale,
    names: Map<MemberId, String>,
    locale: Locale,
): String =
    before
        ?.let { changes(it, after, scale, names, locale) }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(" · ") ?: summary(after, scale, locale)

/** "12.00 · Oct 5, 2026": the amount and date of [entry]. */
fun summary(entry: Entry, scale: Scale, locale: Locale): String =
    "${formatted(entry.amount, scale, locale)} · ${mediumDate(entry.date, locale)}"

/** What [series] does from [from] on as [after] where it did [before], field by field. */
private fun Resources.amended(
    series: SeriesId,
    from: EntryDate,
    before: Segment,
    after: Segment,
    scale: Scale,
    names: Map<MemberId, String>,
    locale: Locale,
): List<String> {
    val id = EntryId.occurrence(series, from)
    val repeats =
        if (before.interval == after.interval && before.mode == after.mode) null
        else {
            getString(
                R.string.history_field_changed,
                getString(R.string.field_repeats),
                rule(after, locale),
                rule(before, locale),
            )
        }
    return listOfNotNull(repeats) +
        changes(before.template.on(id, from), after.template.on(id, from), scale, names, locale)
}

/** "Every month on the 1st, suggested". */
private fun Resources.rule(segment: Segment, locale: Locale): String {
    val mode =
        getString(
            if (segment.mode == Mode.SUGGESTED) R.string.mode_suggested else R.string.mode_automatic
        )
    return "${recurrenceText(segment.interval, locale)}, ${mode.lowercase(locale)}"
}

/**
 * One line for each field in which [after] differs from [before], the same entry (HI-3): "Amount
 * changed to 15.00 (previous 12.00)".
 */
fun Resources.changes(
    before: Entry,
    after: Entry,
    scale: Scale,
    names: Map<MemberId, String>,
    locale: Locale,
): List<String> {
    val diff = EntryChanges.between(before, after)
    val (payersLabel, beneficiariesLabel) = sideLabels(after.kind)
    val lines = mutableListOf<String>()
    fun field(label: Int, change: Change<*>, old: String, new: String) {
        if (change is Change.To) {
            lines += getString(R.string.history_field_changed, getString(label), new, old)
        }
    }
    field(
        R.string.field_kind,
        diff.kind,
        getString(kindLabel(before.kind)),
        getString(kindLabel(after.kind)),
    )
    field(R.string.entry_description, diff.description, before.description, after.description)
    field(
        R.string.entry_date,
        diff.date,
        mediumDate(before.date, locale),
        mediumDate(after.date, locale),
    )
    field(
        R.string.entry_amount,
        diff.amount,
        formatted(before.amount, scale, locale),
        formatted(after.amount, scale, locale),
    )
    field(
        payersLabel,
        diff.payers,
        sideText(before.payers, before, scale, names, locale),
        sideText(after.payers, after, scale, names, locale),
    )
    field(
        beneficiariesLabel,
        diff.beneficiaries,
        sideText(before.beneficiaries, before, scale, names, locale),
        sideText(after.beneficiaries, after, scale, names, locale),
    )
    field(R.string.field_note, diff.note, before.note ?: NONE, after.note ?: NONE)
    if (diff.attachments is Change.To) lines += getString(R.string.history_attachments)
    return lines
}

/** One line for each of [settings], changed from [before] to [after]. */
private fun Resources.settingChanges(
    settings: Set<Setting>,
    before: Settings,
    after: Settings,
    names: Map<MemberId, String>,
    locale: Locale,
): List<String> {
    fun participants(members: List<MemberId>?) =
        members?.let { list ->
            ListFormatter.getInstance(locale).format(list.map { names[it] ?: it.hex.take(8) })
        } ?: NONE
    return Setting.entries
        .filter { it in settings }
        .map { setting ->
            val (label, old, new) =
                when (setting) {
                    Setting.SCALE ->
                        Triple(
                            R.string.field_scale,
                            "${before.scale.digits}",
                            "${after.scale.digits}",
                        )
                    Setting.NAME -> Triple(R.string.group_name, before.name, after.name)
                    Setting.DESCRIPTION ->
                        Triple(
                            R.string.group_description,
                            before.description ?: NONE,
                            after.description ?: NONE,
                        )
                    Setting.EMOJI ->
                        Triple(R.string.group_emoji, before.emoji ?: NONE, after.emoji ?: NONE)
                    Setting.COLOUR ->
                        Triple(
                            R.string.group_colour,
                            before.colour?.text ?: NONE,
                            after.colour?.text ?: NONE,
                        )
                    Setting.DEFAULT_PARTICIPANTS ->
                        Triple(
                            R.string.default_participants,
                            participants(before.defaultParticipants),
                            participants(after.defaultParticipants),
                        )
                }
            getString(R.string.history_field_changed, getString(label), new, old)
        }
}

/** "Anna 15.00, Ben 15.00": each member and their part of [entry]'s amount (SP-7). */
fun sideText(
    side: Side,
    entry: Entry,
    scale: Scale,
    names: Map<MemberId, String>,
    locale: Locale,
): String =
    side.parts(entry.amount).entries.joinToString(", ") { (member, part) ->
        "${names[member] ?: member.hex.take(8)} ${formatted(part.roundedTo(scale), scale, locale)}"
    }
