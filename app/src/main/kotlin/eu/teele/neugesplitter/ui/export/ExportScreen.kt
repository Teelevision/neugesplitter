package eu.teele.neugesplitter.ui.export

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.ui.backend.Choice
import eu.teele.neugesplitter.ui.backend.Found
import eu.teele.neugesplitter.ui.backend.GeneratedPassphrase
import eu.teele.neugesplitter.ui.backend.OwnPassphrase
import java.io.IOException

/**
 * Exports groups to a file the user chooses: whether to encrypt it, with which passphrase, and
 * where to save it (ui-design 3, DA-3, DA-4, PR-12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(viewModel: ExportViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val back = { if (!viewModel.back()) onClose() }
    val file =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { uri ->
            if (uri != null) {
                viewModel.save {
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("no stream for $uri")
                }
            }
        }

    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
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
                    if (state.step != ExportStep.DONE) {
                        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
                    }
                    Spacer(Modifier.weight(1f))
                    when {
                        state.step == ExportStep.DONE ->
                            Button(onClick = onClose) { Text(stringResource(R.string.done)) }
                        state.step == ExportStep.ENCRYPTION && state.encrypted == true ->
                            Button(onClick = viewModel::next) {
                                Text(stringResource(R.string.next))
                            }
                        else ->
                            Button(
                                onClick = { file.launch(viewModel.fileName()) },
                                enabled = state.canSave,
                            ) {
                                Text(stringResource(R.string.export_save))
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
                ExportStep.ENCRYPTION -> Encryption(state, viewModel)
                ExportStep.PASSPHRASE -> Passphrase(state, viewModel)
                ExportStep.DONE ->
                    Found(
                        R.drawable.ic_check,
                        R.string.export_done,
                        listOfNotNull(
                            R.string.export_done_text,
                            R.string.export_done_encrypted.takeIf { state.encrypted == true },
                        ),
                        MaterialTheme.colorScheme.secondaryContainer,
                    )
            }
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
            if (state.failed) {
                Text(
                    stringResource(R.string.export_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private const val ZIP = "application/zip"

@Composable
private fun Encryption(state: ExportState, viewModel: ExportViewModel) {
    Text(pluralStringResource(R.plurals.export_groups, state.count, state.count))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Choice(
            R.drawable.ic_folder,
            R.string.export_plain,
            R.string.export_plain_text,
            selected = state.encrypted == false,
            onClick = { viewModel.encrypted(false) },
        )
        Choice(
            R.drawable.ic_lock,
            R.string.export_encrypted,
            R.string.export_encrypted_text,
            selected = state.encrypted == true,
            onClick = { viewModel.encrypted(true) },
        )
    }
}

@Composable
private fun ColumnScope.Passphrase(state: ExportState, viewModel: ExportViewModel) {
    if (state.custom) {
        OwnPassphrase(state.passphrase, viewModel::passphrase, state.strength)
    } else {
        GeneratedPassphrase(state.words, state.saved, viewModel::saved)
    }
    Text(
        stringResource(R.string.export_passphrase_lost),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
        onClick = { viewModel.custom(!state.custom) },
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(
            stringResource(
                if (state.custom) R.string.passphrase_generate else R.string.passphrase_own
            )
        )
    }
}
