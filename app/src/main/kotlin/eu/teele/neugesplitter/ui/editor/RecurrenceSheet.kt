package eu.teele.neugesplitter.ui.editor

import android.content.res.Resources
import android.icu.text.ListFormatter
import android.icu.text.MessageFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.recurrence.Interval
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.theme.largeText
import java.time.DayOfWeek
import java.time.format.TextStyle as DateStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** How an entry repeats, as the recurrence sheet edits it (RE-2, RE-3). */
data class Recurrence(val interval: Interval, val mode: Mode = Mode.AUTOMATIC) {
    companion object {
        /** What the sheet offers first for an entry on [date]: every month on that day. */
        fun on(date: EntryDate) = Recurrence(Interval.MonthlyOn(setOf(date.value.dayOfMonth)))
    }
}

private enum class Pattern(val label: Int) {
    DAYS(R.string.pattern_days),
    WEEKLY(R.string.pattern_weekly),
    MONTHLY(R.string.pattern_monthly),
}

/** [interval] read back in plain language, without times of day (RE-14, ui-design 5). */
fun Resources.recurrenceText(interval: Interval, locale: Locale): String {
    val list = ListFormatter.getInstance(locale)
    return when (interval) {
        is Interval.EveryDays ->
            getQuantityString(R.plurals.recurrence_days, interval.days, interval.days)
        is Interval.WeeklyOn ->
            getString(
                R.string.recurrence_weekly,
                list.format(
                    week(locale)
                        .filter { it in interval.days }
                        .map { it.getDisplayName(DateStyle.FULL, locale) }
                ),
            )
        is Interval.MonthlyOn ->
            getQuantityString(
                R.plurals.recurrence_monthly,
                interval.months,
                interval.months,
                list.format(interval.days.sorted().map { ordinal(it, locale) }),
            )
    }
}

/**
 * When the next one is, where [interval] does not fall on [date]: the entry there is the first all
 * the same (RE-1). Null where it does.
 */
@Composable
fun offPatternText(date: EntryDate, interval: Interval, locale: Locale): String? {
    if (interval.dates(date.value, date.value).any()) return null
    val next = interval.dates(date.value, interval.horizon(date.value)).firstOrNull() ?: return null
    return stringResource(R.string.recurrence_first, longDate(EntryDate.of(next), locale))
}

/**
 * How an occurrence on [date] stands off its series' schedule, if it does: moved from its [due]
 * date, or due on a date the schedule does not make due, as [scheduled] says (RE-6). Null where
 * neither.
 */
@Composable
fun offDueText(date: EntryDate, due: EntryDate, scheduled: Boolean, locale: Locale): String? =
    when {
        date != due -> stringResource(R.string.occurrence_moved, longDate(due, locale))
        !scheduled -> stringResource(R.string.occurrence_off_schedule)
        else -> null
    }

/**
 * Edits [initial] and hands the result to [onDone]; leaving any other way drops it. With
 * [choosesStart], the first row picks the start date, which is the entry's own [date] (RE-2, RE-3).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurrenceSheet(
    date: EntryDate,
    choosesStart: Boolean,
    initial: Recurrence,
    locale: Locale,
    onStart: (EntryDate) -> Unit,
    onDone: (Recurrence) -> Unit,
    onDismiss: () -> Unit,
) {
    val given = initial.interval
    var pattern by remember {
        mutableStateOf(
            when (given) {
                is Interval.EveryDays -> Pattern.DAYS
                is Interval.WeeklyOn -> Pattern.WEEKLY
                is Interval.MonthlyOn -> Pattern.MONTHLY
            }
        )
    }
    var every by remember { mutableIntStateOf((given as? Interval.EveryDays)?.days ?: 1) }
    var weekdays by remember {
        mutableStateOf((given as? Interval.WeeklyOn)?.days ?: setOf(date.value.dayOfWeek))
    }
    var monthDays by remember {
        mutableStateOf((given as? Interval.MonthlyOn)?.days ?: setOf(date.value.dayOfMonth))
    }
    var months by remember { mutableIntStateOf((given as? Interval.MonthlyOn)?.months ?: 1) }
    var mode by remember { mutableStateOf(initial.mode) }
    var picking by remember { mutableStateOf(false) }
    val interval =
        when (pattern) {
            Pattern.DAYS -> Interval.EveryDays(every)
            Pattern.WEEKLY -> weekdays.takeIf { it.isNotEmpty() }?.let(Interval::WeeklyOn)
            Pattern.MONTHLY ->
                monthDays.takeIf { it.isNotEmpty() }?.let { Interval.MonthlyOn(it, months) }
        }
    val padded = Modifier.fillMaxWidth().padding(horizontal = 24.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.repeats),
                style = MaterialTheme.typography.titleLarge,
                modifier = padded,
            )
            if (choosesStart) {
                val label =
                    @Composable { modifier: Modifier ->
                        Text(
                            stringResource(R.string.recurrence_start),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = modifier,
                        )
                    }
                val start =
                    @Composable {
                        OutlinedButton(onClick = { picking = true }) {
                            Text(longDate(date, locale))
                        }
                    }
                if (largeText()) {
                    Column(padded) {
                        label(Modifier)
                        start()
                    }
                } else {
                    Row(padded, verticalAlignment = Alignment.CenterVertically) {
                        label(Modifier.weight(1f))
                        start()
                    }
                }
            }
            Column(padded, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segments(Pattern.entries, pattern, { it.label }, { pattern = it })
                when (pattern) {
                    Pattern.DAYS ->
                        Stepper(
                            pluralStringResource(R.plurals.recurrence_days, every, every),
                            stringResource(R.string.recurrence_fewer_days),
                            stringResource(R.string.recurrence_more_days),
                            every,
                        ) {
                            every = it
                        }
                    Pattern.WEEKLY ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (day in week(locale)) {
                                FilterChip(
                                    selected = day in weekdays,
                                    onClick = { weekdays = weekdays.toggled(day) },
                                    label = { Text(day.getDisplayName(DateStyle.SHORT, locale)) },
                                )
                            }
                        }
                    Pattern.MONTHLY -> {
                        Stepper(
                            pluralStringResource(R.plurals.recurrence_months, months, months),
                            stringResource(R.string.recurrence_fewer_months),
                            stringResource(R.string.recurrence_more_months),
                            months,
                        ) {
                            months = it
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (day in 1..31) {
                                FilterChip(
                                    selected = day in monthDays,
                                    onClick = { monthDays = monthDays.toggled(day) },
                                    label = { Text(day.toString()) },
                                )
                            }
                        }
                        if (monthDays.any { it > 28 }) {
                            Text(
                                stringResource(R.string.recurrence_month_end),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Column(padded, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segments(Mode.entries, mode, ::modeLabel, { mode = it })
                Text(
                    stringResource(
                        if (mode == Mode.AUTOMATIC) R.string.mode_automatic_hint
                        else R.string.mode_suggested_hint
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Row(padded, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        interval
                            ?.let { LocalResources.current.recurrenceText(it, locale) }
                            .orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    interval
                        ?.takeIf { choosesStart }
                        ?.let { offPatternText(date, it, locale) }
                        ?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
                TextButton(
                    onClick = { interval?.let { onDone(Recurrence(it, mode)) } },
                    enabled = interval != null,
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
    if (picking) {
        DatePick(
            date = date,
            onPicked = {
                onStart(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

internal fun modeLabel(mode: Mode) =
    when (mode) {
        Mode.AUTOMATIC -> R.string.mode_automatic
        Mode.SUGGESTED -> R.string.mode_suggested
    }

/** [label] between buttons that take one from [count], down to one, or add one to it. */
@Composable
private fun Stepper(
    label: String,
    fewer: String,
    more: String,
    count: Int,
    onCount: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onCount(count - 1) }, enabled = count > 1) {
            Icon(painterResource(R.drawable.ic_minus), fewer)
        }
        Text(label, style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onCount(count + 1) }) {
            Icon(painterResource(R.drawable.ic_add), more)
        }
    }
}

/** The days of the week, starting where weeks start in [locale]. */
private fun week(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L..6L).map(first::plus)
}

private fun ordinal(day: Int, locale: Locale): String =
    MessageFormat("{0,ordinal}", locale).format(arrayOf<Any>(day))

private fun <T> Set<T>.toggled(item: T) = if (item in this) this - item else this + item
