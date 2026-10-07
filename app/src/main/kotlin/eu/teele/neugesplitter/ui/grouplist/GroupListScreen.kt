package eu.teele.neugesplitter.ui.grouplist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.storage.ListedGroup
import eu.teele.neugesplitter.ui.GroupAvatar
import eu.teele.neugesplitter.ui.formatted

/** The root screen: every group on this device (GR-1, ui-design 3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(viewModel: GroupListViewModel, onNewGroup: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selecting = state.selected.isNotEmpty()
    var confirming by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    Scaffold(
        topBar = {
            if (selecting) {
                val count = state.selected.size
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.groups_selected, count, count)) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                stringResource(R.string.clear_selection),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { confirming = true }) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                stringResource(R.string.delete),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(title = { Text(stringResource(R.string.app_name)) })
            }
        },
        floatingActionButton = {
            if (!selecting) {
                FloatingActionButton(onClick = onNewGroup) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.new_group))
                }
            }
        },
    ) { padding ->
        val groups = state.groups
        when {
            groups == null -> Unit
            groups.isEmpty() -> Empty(onNewGroup, Modifier.padding(padding))
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding =
                        PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(groups, key = { it.id.hex }) { group ->
                        GroupCard(
                            group = group,
                            selected = group.id in state.selected,
                            // Tapping opens the group once its overview exists (ui-design 3).
                            onClick = { if (selecting) viewModel.toggle(group.id) },
                            onLongClick = { viewModel.toggle(group.id) },
                        )
                    }
                }
        }
    }

    if (confirming && selecting) {
        val count = state.selected.size
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(pluralStringResource(R.plurals.delete_groups_title, count, count)) },
            text = { Text(pluralStringResource(R.plurals.delete_groups_text, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        viewModel.deleteSelected()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Empty(onNewGroup: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier.size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_branch),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.group_list_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNewGroup, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.new_group))
        }
    }
}

/** One group: its emoji and colour, its name, and where the user stands in it (GR-2, BA-7). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupCard(
    group: ListedGroup,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colours = MaterialTheme.colorScheme
    OutlinedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(CardDefaults.outlinedShape)
                .then(
                    if (onClick != null) {
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else Modifier
                ),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor =
                    if (selected) colours.primaryContainer else colours.surfaceContainerLow
            ),
        border = CardDefaults.outlinedCardBorder().copy(width = if (selected) 2.dp else 1.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GroupAvatar(group.name, group.emoji, group.colour)
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                summary(group)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colours.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = colours.primary,
                )
            }
        }
    }
}

/** "You are owed 64.20", with the amount in bold tabular figures (BA-7, ui-design 9). */
@Composable
private fun summary(group: ListedGroup): AnnotatedString? {
    if (group.entries == 0L) return AnnotatedString(stringResource(R.string.group_no_entries))
    val own = group.own ?: return null
    if (own.isZero) return AnnotatedString(stringResource(R.string.group_settled))
    val amount = formatted(own, group.scale, LocalConfiguration.current.locales[0])
    val text =
        stringResource(if (own.isPositive) R.string.group_owed else R.string.group_owe, amount)
    val start = text.indexOf(amount)
    return buildAnnotatedString {
        append(text)
        if (start >= 0) {
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                start,
                start + amount.length,
            )
        }
    }
}
