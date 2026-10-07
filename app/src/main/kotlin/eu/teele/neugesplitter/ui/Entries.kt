package eu.teele.neugesplitter.ui

import android.icu.text.ListFormatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.ListedEntry
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/** Each kind has its own icon, so that it never rests on colour alone (UX-6). */
@DrawableRes
fun kindIcon(kind: Kind): Int =
    when (kind) {
        Kind.EXPENSE -> R.drawable.ic_expense
        Kind.INCOME -> R.drawable.ic_income
        Kind.TRANSFER -> R.drawable.ic_transfer
    }

@StringRes
fun kindLabel(kind: Kind): Int =
    when (kind) {
        Kind.EXPENSE -> R.string.kind_expense
        Kind.INCOME -> R.string.kind_income
        Kind.TRANSFER -> R.string.kind_transfer
    }

/** What the payer and beneficiary sides are called; a transfer goes from and to (EN-7). */
fun sideLabels(kind: Kind): Pair<Int, Int> =
    when (kind) {
        Kind.EXPENSE -> R.string.side_paid_by to R.string.side_for
        Kind.INCOME -> R.string.side_received_by to R.string.side_for
        Kind.TRANSFER -> R.string.side_from to R.string.side_to
    }

/** "Monday, 31 August 2026", as [locale] writes it. */
fun longDate(date: EntryDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date.value)

/**
 * Income and transfers carry their own icon, and income a sign, so the kind never rests on colour
 * alone (UX-6). Names truncate before the amount does (ui-design 9).
 */
@Composable
fun EntryRow(entry: ListedEntry, scale: Scale, locale: Locale, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    val by =
        when (entry.kind) {
            Kind.EXPENSE -> R.string.entry_paid_by
            Kind.INCOME -> R.string.entry_received_by
            Kind.TRANSFER -> R.string.entry_transfer_from
        }
    val payers = ListFormatter.getInstance(locale).format(entry.payers)
    val meta =
        listOfNotNull(
                stringResource(by, payers),
                stringResource(R.string.entry_edited).takeIf { entry.edited },
            )
            .joinToString(" · ")

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(40.dp).clip(CircleShape).background(colours.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(kindIcon(entry.kind)),
                stringResource(kindLabel(entry.kind)),
                tint = colours.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.description,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.repeats) {
                    Icon(
                        painterResource(R.drawable.ic_repeat),
                        stringResource(R.string.entry_repeats),
                        tint = colours.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
            }
            Text(
                meta,
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val amount = formatted(entry.amount, scale, locale)
            Text(
                if (entry.kind == Kind.INCOME) "+$amount" else amount,
                style = MaterialTheme.typography.titleMedium.merge(TABULAR),
            )
            val own = entry.own
            if (own != null && !own.isZero && entry.kind != Kind.TRANSFER) {
                Text(
                    stringResource(R.string.entry_own_share, formatted(own, scale, locale)),
                    style = MaterialTheme.typography.bodySmall.merge(TABULAR),
                    color = colours.onSurfaceVariant,
                )
            }
        }
    }
}
