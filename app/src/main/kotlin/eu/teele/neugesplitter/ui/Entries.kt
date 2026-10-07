package eu.teele.neugesplitter.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.EntryDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
