package eu.teele.neugesplitter.ui.tidy

import android.icu.text.ListFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Entry
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.Proposal
import eu.teele.neugesplitter.ui.category.CategorySheet
import eu.teele.neugesplitter.ui.category.categoryName
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.history.HistoryRow
import eu.teele.neugesplitter.ui.history.mediumDate
import eu.teele.neugesplitter.ui.history.told
import java.util.Locale
import kotlinx.coroutines.launch

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/**
 * Tidying up a group's history (TI-1): one tab of expenses that read like incomes, one of entries
 * without a category, each proposed in batches of the same description. A shared group shows what
 * its activity will show before anything is written (TI-5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TidyScreen(viewModel: TidyViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview = state.preview
    if (preview != null) {
        PreviewScreen(state, preview, onCancel = viewModel::cancel, onConfirm = viewModel::confirm)
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<CategoryBatch?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val applied = state.applied
    val message = applied?.let { pluralStringResource(R.plurals.tidy_applied, it, it) }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        scope.launch { snackbar.showSnackbar(message) }
        viewModel.appliedTaken()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tidy_title)) },
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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = {
                        Text(
                            stringResource(
                                R.string.tidy_tab_income,
                                state.incomes.sumOf { it.items.size },
                            )
                        )
                    },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        Text(
                            stringResource(
                                R.string.tidy_tab_categories,
                                state.uncategorised.sumOf { it.entries.size },
                            )
                        )
                    },
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (tab == 0) {
                    item(key = "explain") { Explanation(R.string.tidy_income_explain) }
                    if (state.loaded && state.incomes.isEmpty()) {
                        item(key = "empty") { Explanation(R.string.tidy_income_empty) }
                    }
                    items(state.incomes, key = { "income ${it.key}" }) { batch ->
                        IncomeCard(
                            batch,
                            state,
                            locale,
                            onToggle = viewModel::toggle,
                            onDismiss = {
                                viewModel.dismiss(Proposal.INCOME, batch.items.map { it.before.id })
                            },
                            onApply = { viewModel.makeIncome(batch) },
                        )
                    }
                } else {
                    item(key = "explain") { Explanation(R.string.tidy_categories_explain) }
                    if (state.loaded && state.uncategorised.isEmpty()) {
                        item(key = "empty") { Explanation(R.string.tidy_categories_empty) }
                    }
                    items(state.uncategorised, key = { "category ${it.key}" }) { batch ->
                        CategoryCard(
                            batch,
                            state,
                            locale,
                            onToggle = viewModel::toggle,
                            onChoose = { viewModel.choose(batch, it) },
                            onMore = { picking = batch },
                            onDismiss = {
                                viewModel.dismiss(Proposal.CATEGORY, batch.entries.map { it.id })
                            },
                            onApply = { viewModel.categorise(batch) },
                        )
                    }
                }
            }
        }
    }

    picking?.let { batch ->
        CategorySheet(
            categories = state.categories,
            ranked = emptyList(),
            selected = listOfNotNull(state.choice(batch)),
            onChange = { chosen -> chosen.firstOrNull()?.let { viewModel.choose(batch, it) } },
            onDismiss = { picking = null },
            single = true,
        )
    }
}

@Composable
private fun Explanation(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private fun names(members: List<MemberId>, state: TidyState, locale: Locale): String =
    ListFormatter.getInstance(locale).format(members.map { state.names[it] ?: it.hex.take(8) })

@Composable
private fun BatchHeading(description: String, count: Int) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            description,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            pluralStringResource(R.plurals.tidy_entries, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IncomeCard(
    batch: IncomeBatch,
    state: TidyState,
    locale: Locale,
    onToggle: (EntryId) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        BatchHeading(batch.items.first().before.description, batch.items.size)
        for (item in batch.items) {
            val before = item.before
            val after = item.after
            val supporting =
                listOf(
                        mediumDate(before.date, locale) +
                            " · " +
                            stringResource(
                                R.string.entry_paid_by,
                                names(before.payers.members, state, locale),
                            ),
                        stringResource(
                            R.string.tidy_income_becomes,
                            names(after.payers.members, state, locale),
                        ) +
                            ", " +
                            stringResource(
                                R.string.tidy_income_for,
                                names(after.beneficiaries.members, state, locale),
                            ),
                    )
                    .joinToString("\n")
            TidyRow(before, state, locale, supporting, before.id !in state.excluded) {
                onToggle(before.id)
            }
        }
        Actions(
            dismiss = R.string.tidy_not_income,
            apply = R.string.tidy_make_income,
            enabled = batch.items.any { it.before.id !in state.excluded },
            onDismiss = onDismiss,
            onApply = onApply,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryCard(
    batch: CategoryBatch,
    state: TidyState,
    locale: Locale,
    onToggle: (EntryId) -> Unit,
    onChoose: (CategoryId) -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val choice = state.choice(batch)
    val known = state.categories.associateBy { it.id }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        BatchHeading(batch.entries.first().description, batch.entries.size)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            val shown = (batch.suggested + listOfNotNull(choice)).distinct()
            for (id in shown) {
                val category = known[id] ?: continue
                FilterChip(
                    selected = id == choice,
                    onClick = { onChoose(id) },
                    label = { Text("${category.emoji} ${categoryName(category)}") },
                )
            }
            AssistChip(onClick = onMore, label = { Text(stringResource(R.string.category_choose)) })
        }
        for (entry in batch.entries) {
            val supporting =
                mediumDate(entry.date, locale) +
                    " · " +
                    stringResource(
                        R.string.entry_paid_by,
                        names(entry.payers.members, state, locale),
                    )
            TidyRow(entry, state, locale, supporting, entry.id !in state.excluded) {
                onToggle(entry.id)
            }
        }
        Actions(
            dismiss = R.string.tidy_skip,
            apply = R.string.tidy_apply,
            enabled = choice != null && batch.entries.any { it.id !in state.excluded },
            onDismiss = onDismiss,
            onApply = onApply,
        )
    }
}

@Composable
private fun TidyRow(
    entry: Entry,
    state: TidyState,
    locale: Locale,
    supporting: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(entry.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(supporting) },
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        trailingContent = {
            Text(
                formatted(entry.amount, state.scale, locale),
                style = MaterialTheme.typography.bodyLarge.merge(TABULAR),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier.toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
    )
}

@Composable
private fun Actions(
    dismiss: Int,
    apply: Int,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TextButton(onClick = onDismiss) { Text(stringResource(dismiss)) }
        Button(onClick = onApply, enabled = enabled) { Text(stringResource(apply)) }
    }
}

/** The changes to a shared group as its activity will show them, to confirm or cancel (TI-5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(
    state: TidyState,
    preview: Preview,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalResources.current
    val count = preview.changes.size
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tidy_preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel))
                    }
                },
            )
        },
        bottomBar = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                Button(onClick = onConfirm) {
                    Text(pluralStringResource(R.plurals.tidy_confirm, count, count))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "explain") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        pluralStringResource(R.plurals.tidy_preview_explain, count, count),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.tidy_balances_same),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(preview.happenings) { happening ->
                HistoryRow(
                    resources.told(happening, state.names, state.scale, locale),
                    happening.at,
                    locale,
                    detailLines = Int.MAX_VALUE,
                )
            }
        }
    }
}
