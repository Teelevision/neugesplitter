package eu.teele.neugesplitter.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.category.Category
import java.text.Collator

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CategorySettingsScreen(
    viewModel: CategorySettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Category?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf("📦") }
    var showDeleted by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val collator = remember(locale) { Collator.getInstance(locale) }
    val categories =
        state.categories.map { category ->
            category to
                (category.name
                    ?: category.builtin?.let { stringResource(categoryNameResource(it)) }.orEmpty())
        }
    val visibleCategories =
        remember(categories, showDeleted, collator) {
            categories
                .filter { (category, _) -> showDeleted || !category.deleted }
                .sortedWith { left, right -> collator.compare(left.second, right.second) }
        }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.category_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleted = !showDeleted }) {
                        Icon(
                            painterResource(
                                if (showDeleted) R.drawable.ic_visibility_off
                                else R.drawable.ic_visibility
                            ),
                            contentDescription =
                                stringResource(
                                    if (showDeleted) R.string.category_hide_deleted
                                    else R.string.category_show_deleted
                                ),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    name = ""
                    emoji = "📦"
                    adding = true
                }
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.category_add),
                )
            }
        },
    ) { padding ->
        if (!state.loaded) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(visibleCategories, key = { it.first.id.hex }) { (category, label) ->
                    CategoryRow(
                        category = category,
                        label = label,
                        onEdit = {
                            editing = category
                            name = label
                            emoji = category.emoji
                        },
                        onDelete = { viewModel.delete(category.id) },
                        onRestore = { viewModel.restore(category.id) },
                    )
                }
            }
        }
    }

    val edited = editing
    if (adding || edited != null) {
        val localized = edited?.builtin?.let { stringResource(categoryNameResource(it)) }
        val shownName = name
        CategoryDialog(
            title = stringResource(if (adding) R.string.category_add else R.string.category_edit),
            name = shownName,
            emoji = emoji,
            onName = { name = it },
            onEmoji = { emoji = it },
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = {
                if (adding) viewModel.create(name, emoji)
                else edited?.let { viewModel.update(it.id, shownName, emoji, localized) }
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    label: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.emoji, style = MaterialTheme.typography.headlineSmall)
        Text(
            checkNotNull(label),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (category.deleted) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        IconButton(onClick = onEdit) {
            Icon(
                painterResource(R.drawable.ic_edit),
                contentDescription = stringResource(R.string.category_edit),
            )
        }
        IconButton(onClick = if (category.deleted) onRestore else onDelete) {
            Icon(
                painterResource(
                    if (category.deleted) R.drawable.ic_history else R.drawable.ic_delete
                ),
                contentDescription =
                    stringResource(
                        if (category.deleted) R.string.category_restore else R.string.delete
                    ),
            )
        }
    }
}

@Composable
private fun CategoryDialog(
    title: String,
    name: String,
    emoji: String,
    onName: (String) -> Unit,
    onEmoji: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = onEmoji,
                    label = { Text(stringResource(R.string.category_emoji)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = name.isNotBlank() && emoji.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
