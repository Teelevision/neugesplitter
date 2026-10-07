package eu.teele.neugesplitter.ui.overview

import android.icu.text.ListFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.ListedEntry
import eu.teele.neugesplitter.storage.Overview
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.position
import eu.teele.neugesplitter.ui.theme.groupColour
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/** A group's total spending and its entries, newest first (ui-design 4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(viewModel: OverviewViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val overview = state.overview

    // Deleted while open, which only another screen can do.
    LaunchedEffect(state) { if (state.loaded && overview == null) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (overview != null) {
                        Text(
                            listOfNotNull(overview.emoji, overview.name).joinToString(" "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        if (overview != null) {
            Entries(overview, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun Entries(overview: Overview, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val days = overview.entries.groupBy { it.date }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") { Header(overview, locale) }
        if (days.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.group_no_entries),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                )
            }
        }
        for ((date, entries) in days) {
            item(key = date.toString()) { Day(date, locale) }
            items(entries, key = { it.id.hex }) { EntryRow(it, overview.scale, locale) }
        }
    }
}

/** The total and the user's own position, on the group's colour (BA-7, ui-design 8). */
@Composable
private fun Header(overview: Overview, locale: Locale) {
    Column(
        modifier =
            Modifier.padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(groupColour(overview.colour))
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.total_spending),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        Text(
            formatted(overview.total, overview.scale, locale),
            style = MaterialTheme.typography.headlineMedium.merge(TABULAR),
            color = Color.White,
        )
        // Nothing to be settled before anything happened, as on the group list.
        overview.own
            ?.takeIf { overview.entries.isNotEmpty() }
            ?.let {
                Text(
                    position(it, overview.scale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
    }
}

@Composable
private fun Day(date: EntryDate, locale: Locale) {
    Text(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date.value),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics {
                heading()
            },
    )
}

/**
 * Income and transfers carry their own icon, and income a sign, so the kind never rests on colour
 * alone (UX-6). Names truncate before the amount does (ui-design 9).
 */
@Composable
private fun EntryRow(entry: ListedEntry, scale: Scale, locale: Locale) {
    val colours = MaterialTheme.colorScheme
    val (icon, label, by) =
        when (entry.kind) {
            Kind.EXPENSE ->
                Triple(R.drawable.ic_expense, R.string.kind_expense, R.string.entry_paid_by)
            Kind.INCOME ->
                Triple(R.drawable.ic_income, R.string.kind_income, R.string.entry_received_by)
            Kind.TRANSFER ->
                Triple(R.drawable.ic_transfer, R.string.kind_transfer, R.string.entry_transfer_from)
        }
    val payers = ListFormatter.getInstance(locale).format(entry.payers)
    val meta =
        listOfNotNull(
                stringResource(by, payers),
                stringResource(R.string.entry_edited).takeIf { entry.edited },
            )
            .joinToString(" · ")

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(40.dp).clip(CircleShape).background(colours.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                stringResource(label),
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
