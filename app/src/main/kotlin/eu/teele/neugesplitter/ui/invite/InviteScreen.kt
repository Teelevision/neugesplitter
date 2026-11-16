package eu.teele.neugesplitter.ui.invite

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.ui.backend.Found

/**
 * A shared group's invitation as a QR code, to scan when joining (ui-design 4, BE-18), shown only
 * once the warning that it holds the group's secrets is read. The screen cannot be captured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteScreen(viewModel: InviteViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The code shows only once its warning has been read.
    var shown by rememberSaveable { mutableStateOf(false) }
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.invite)) },
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
    ) { padding ->
        if (!state.loaded) return@Scaffold
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val invitation = state.invitation
            if (invitation == null) {
                Text(stringResource(R.string.invite_unavailable))
                return@Column
            }
            val warning =
                @Composable {
                    Found(
                        R.drawable.ic_warning,
                        R.string.invite_secret_title,
                        listOf(
                            if (invitation.passphrase == null) R.string.invite_secret_text
                            else R.string.invite_secret_passphrase_text,
                            R.string.invite_screenshots,
                        ),
                        MaterialTheme.colorScheme.errorContainer,
                    )
                }
            if (!shown) {
                warning()
                Button(
                    onClick = { shown = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.invite_show))
                }
                return@Column
            }
            Text(stringResource(R.string.invite_text))
            QrCode(
                invitation.encoded(),
                stringResource(R.string.invite_code),
                Modifier.widthIn(max = 360.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            )
            if (state.encrypted && invitation.passphrase == null) {
                Text(
                    stringResource(R.string.invite_no_passphrase),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            warning()
        }
    }
}
