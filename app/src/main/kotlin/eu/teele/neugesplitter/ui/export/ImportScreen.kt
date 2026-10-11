package eu.teele.neugesplitter.ui.export

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.ui.GroupAvatar
import eu.teele.neugesplitter.ui.backend.Found
import eu.teele.neugesplitter.ui.backend.Secret
import eu.teele.neugesplitter.ui.join.GroupSummary
import eu.teele.neugesplitter.ui.join.WhoFields
import eu.teele.neugesplitter.ui.join.candidates
import eu.teele.neugesplitter.ui.join.sizeOf

/**
 * Imports groups from an export file: its passphrase if it is encrypted, which of its groups, and
 * who the user is in each (ui-design 3, DA-3).
 *
 * @param onDone called with the groups imported, once every one chosen is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: ImportViewModel, onClose: () -> Unit, onDone: (List<GroupId>) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = { if (!viewModel.back()) onClose() }

    LaunchedEffect(state.done) { state.done?.let(onDone) }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = back) { Text(stringResource(R.string.back)) }
                    Spacer(Modifier.weight(1f))
                    if (state.step != ImportStep.FAILED && state.step != ImportStep.READING) {
                        Button(onClick = viewModel::next, enabled = state.canContinue) {
                            Text(
                                stringResource(
                                    if (state.step == ImportStep.WHO) R.string.import_action
                                    else R.string.next
                                )
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                ImportStep.READING -> Unit
                ImportStep.PASSPHRASE -> Passphrase(state, viewModel)
                ImportStep.FAILED -> Problem(state.problem)
                ImportStep.GROUPS -> Groups(state, viewModel)
                ImportStep.WHO -> {
                    val group = state.group ?: return@Column
                    GroupSummary(group)
                    WhoFields(
                        candidates(group),
                        state.member,
                        state.adding,
                        state.name,
                        state.rejected,
                        viewModel::choose,
                        viewModel::adding,
                        viewModel::name,
                    )
                }
            }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.import_reading))
                }
            }
        }
    }
}

@Composable
private fun Passphrase(state: ImportState, viewModel: ImportViewModel) {
    var shown by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.import_passphrase_text))
    Secret(state.passphrase, viewModel::passphrase, R.string.passphrase, shown, { shown = it })
    if (state.wrong) {
        Text(
            stringResource(R.string.import_wrong_passphrase),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Problem(problem: ImportProblem?) {
    val (title, text) =
        when (problem) {
            ImportProblem.UNREADABLE ->
                R.string.import_unreadable to R.string.import_unreadable_text
            ImportProblem.UNSUPPORTED ->
                R.string.import_unsupported to R.string.import_unsupported_text
            ImportProblem.EMPTY -> R.string.import_empty to R.string.import_empty_text
            null -> return
        }
    Found(R.drawable.ic_warning, title, listOf(text), MaterialTheme.colorScheme.errorContainer)
}

/** The groups in the file, to choose which to import; those that cannot be, greyed out. */
@Composable
private fun Groups(state: ImportState, viewModel: ImportViewModel) {
    val colours = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.import_choose), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.import_choose_text), color = colours.onSurfaceVariant)
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        state.candidates.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            val group = candidate.group
            val checked = index in state.chosen
            ListItem(
                headlineContent = {
                    Text(group?.name ?: stringResource(R.string.import_group_unknown))
                },
                supportingContent = {
                    Text(
                        when {
                            candidate.imported -> stringResource(R.string.import_imported)
                            candidate.unsupported ->
                                stringResource(R.string.import_group_unsupported)
                            group == null -> stringResource(R.string.import_group_unreadable)
                            else -> sizeOf(group)
                        }
                    )
                },
                leadingContent =
                    group?.let { { GroupAvatar(it.name, it.emoji, it.colour) } }
                        ?: {
                            Icon(painterResource(R.drawable.ic_warning), null)
                        },
                trailingContent = {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = candidate.importable,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier.toggleable(
                        value = checked,
                        enabled = candidate.importable,
                        role = Role.Checkbox,
                        onValueChange = { viewModel.toggle(index) },
                    ),
            )
        }
    }
}
