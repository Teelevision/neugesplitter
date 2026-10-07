package eu.teele.neugesplitter.ui.overview

import android.icu.text.ListFormatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.ListedEntry
import eu.teele.neugesplitter.storage.Overview
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindIcon
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.position
import eu.teele.neugesplitter.ui.theme.groupColour
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/** A group's total spending and its entries, newest first (ui-design 4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    viewModel: OverviewViewModel,
    deleteRequested: EntryId?,
    onDeleteTaken: () -> Unit,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (EntryId) -> Unit,
    onEdit: (EntryId) -> Unit,
    onDuplicate: (EntryId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val overview = state.overview
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf<ListedEntry?>(null) }
    val deleted = stringResource(R.string.entry_deleted)
    val undo = stringResource(R.string.undo)

    // Deleted while open, which only another screen can do.
    LaunchedEffect(state) { if (state.loaded && overview == null) onBack() }
    LaunchedEffect(deleteRequested) {
        if (deleteRequested != null) {
            viewModel.delete(deleteRequested)
            onDeleteTaken()
        }
    }
    LaunchedEffect(state.deleting) {
        if (state.deleting == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(deleted, undo, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.undoGone()
    }

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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.new_entry))
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (overview != null) {
            Entries(
                overview,
                onOpen = onOpen,
                onMenu = { menu = it },
                onDelete = viewModel::delete,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    menu?.let { entry ->
        ModalBottomSheet(onDismissRequest = { menu = null }) {
            Text(
                entry.description,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetAction(R.drawable.ic_edit, R.string.edit) {
                menu = null
                onEdit(entry.id)
            }
            SheetAction(R.drawable.ic_content_copy, R.string.duplicate) {
                menu = null
                onDuplicate(entry.id)
            }
            SheetAction(R.drawable.ic_delete, R.string.delete) {
                menu = null
                viewModel.delete(entry.id)
            }
        }
    }
}

@Composable
private fun SheetAction(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Entries(
    overview: Overview,
    onOpen: (EntryId) -> Unit,
    onMenu: (ListedEntry) -> Unit,
    onDelete: (EntryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val days = overview.entries.groupBy { it.date }
    // Room under the last entry for the FAB.
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 96.dp)) {
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
            items(entries, key = { it.id.hex }) { entry ->
                // Swiping left deletes, with undo; swiping right does nothing (ui-design 4).
                val threshold = SwipeToDismissBoxDefaults.positionalThreshold
                // Not saveable: a row brought back by undo must not come back swiped away.
                val swipe = remember {
                    SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold)
                }
                SwipeToDismissBox(
                    state = swipe,
                    backgroundContent = { DeleteBackground() },
                    enableDismissFromStartToEnd = false,
                    onDismiss = { onDelete(entry.id) },
                ) {
                    EntryRow(
                        entry,
                        overview.scale,
                        locale,
                        Modifier.background(MaterialTheme.colorScheme.surface)
                            .combinedClickable(
                                onClick = { onOpen(entry.id) },
                                onLongClick = { onMenu(entry) },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteBackground() {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            painterResource(R.drawable.ic_delete),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
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
        longDate(date, locale),
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
private fun EntryRow(
    entry: ListedEntry,
    scale: Scale,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
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
