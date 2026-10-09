package eu.teele.neugesplitter.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.storage.settings.Failure
import eu.teele.neugesplitter.sync.writer.Halt

/**
 * Why a group stopped syncing, that it still works here, and what would make the storage agree with
 * this device again (SY-15, ui-design 6). Nothing here happens by itself; restoring from this
 * device (SY-11, TR-40a) comes later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncProblemScreen(viewModel: SyncProblemViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colours = MaterialTheme.colorScheme

    // No longer shared, or deleted, while open.
    LaunchedEffect(state) { if (state.loaded && state.sync == null) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_problem)) },
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
        val sync = state.sync ?: return@Scaffold
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val halted = sync.halted
            if (halted == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_check), null, tint = colours.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.sync_resumed),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(stringResource(R.string.sync_resumed_text))
                Button(onClick = onBack) { Text(stringResource(R.string.done)) }
                return@Column
            }
            Row {
                Icon(painterResource(R.drawable.ic_warning), null, tint = colours.error)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(happened(halted.reason)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(stringResource(R.string.halt_usable))
            Text(
                stringResource(
                    if (halted.reason == Halt.UNSUPPORTED) R.string.halt_repair_update
                    else R.string.halt_repair_provider
                )
            )
            // Names the object for whoever repairs the location with the provider's tools.
            Text(
                stringResource(R.string.halt_object, halted.number.name),
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
            )
            sync.failure?.let { failure ->
                val (title, text) = failed(failure)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(title), color = colours.error)
                    Text(stringResource(text), color = colours.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = viewModel::check) {
                Text(stringResource(R.string.check_again))
            }
        }
    }
}

/** That a group stopped syncing, leading to why (SY-15). */
@Composable
fun SyncHaltedCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(painterResource(R.drawable.ic_warning), null, tint = colours.error)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.sync_halted),
                    style = MaterialTheme.typography.titleMedium,
                    color = colours.error,
                )
                Text(stringResource(R.string.sync_halted_text))
            }
        }
    }
}

/** What a failed pass is reported as, title and explanation (BE-7). */
fun failed(failure: Failure): Pair<Int, Int> =
    when (failure) {
        Failure.UNREACHABLE -> R.string.setup_unreachable to R.string.setup_unreachable_text
        Failure.DENIED -> R.string.setup_denied to R.string.setup_denied_text
        Failure.MISSING -> R.string.setup_missing to R.string.setup_missing_text
        Failure.FAILED -> R.string.setup_failed to R.string.setup_failed_text
        Failure.LOCKED -> R.string.sync_locked to R.string.sync_locked_text
    }

private fun happened(halt: Halt): Int =
    when (halt) {
        Halt.TRUNCATED -> R.string.halt_truncated
        Halt.GAP -> R.string.halt_gap
        Halt.COMPROMISED -> R.string.halt_compromised
        Halt.MALFORMED -> R.string.halt_malformed
        Halt.UNSUPPORTED -> R.string.halt_unsupported
    }
