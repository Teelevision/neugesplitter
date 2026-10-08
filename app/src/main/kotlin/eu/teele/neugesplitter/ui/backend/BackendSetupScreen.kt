package eu.teele.neugesplitter.ui.backend

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.probe.Contents
import eu.teele.neugesplitter.sync.probe.Verdict
import kotlinx.coroutines.launch

/** No autocorrect and no capitals: these are keys and addresses, not words. */
private val VERBATIM =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
        imeAction = ImeAction.Next,
    )

/**
 * Where a group is shared, checked, and with what protection (ui-design 6): the location, the test,
 * what is there, encryption, the passphrase, and the acknowledgement (BE-7, BE-9, BE-12, PR-5,
 * PR-11, PR-12).
 *
 * @param onDone called once the group is shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendSetupScreen(viewModel: BackendSetupViewModel, onClose: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = { if (!viewModel.back()) onClose() }

    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler(onBack = back)

    val passphrase = state.step == SetupStep.PASSPHRASE
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (passphrase) R.string.passphrase else R.string.setup_title
                        )
                    )
                },
                navigationIcon = {
                    if (passphrase) {
                        IconButton(onClick = back) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                stringResource(R.string.back),
                            )
                        }
                    } else {
                        IconButton(onClick = onClose) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                stringResource(R.string.close),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                HorizontalDivider()
                if (passphrase) {
                    Text(
                        stringResource(R.string.passphrase_lost),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    Buttons(state, viewModel, back)
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
                SetupStep.LOCATION -> Fields(state, viewModel)
                SetupStep.TEST -> Test(state)
                SetupStep.CONTENTS -> WhatIsThere(state)
                SetupStep.ENCRYPTION -> Encryption(state, viewModel)
                SetupStep.PASSPHRASE -> Passphrase(state, viewModel)
                SetupStep.CONFIRM -> Confirm(state)
            }
        }
    }
}

@Composable
private fun Buttons(state: BackendSetupState, viewModel: BackendSetupViewModel, back: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
        Spacer(Modifier.weight(1f))
        val unusable =
            state.step == SetupStep.CONTENTS && !state.canContinue && state.contents != null
        if (unusable) {
            TextButton(onClick = viewModel::elsewhere) {
                Text(stringResource(R.string.setup_elsewhere))
            }
        }
        when {
            state.step == SetupStep.CONFIRM && state.location.plainHttp ->
                OutlinedButton(
                    onClick = viewModel::next,
                    enabled = state.canContinue,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.setup_http_confirm))
                }
            else ->
                Button(onClick = viewModel::next, enabled = state.canContinue) {
                    Text(
                        stringResource(
                            when (state.step) {
                                SetupStep.LOCATION -> R.string.setup_test
                                SetupStep.CONFIRM -> R.string.setup_confirm
                                else -> R.string.next
                            }
                        )
                    )
                }
        }
    }
}

@Composable
private fun Fields(state: BackendSetupState, viewModel: BackendSetupViewModel) {
    val location = state.location
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var nothing by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableStateOf(false) }

    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setup_paste_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.setup_paste_text))
            if (nothing) {
                Text(
                    stringResource(R.string.setup_paste_nothing),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val clip = clipboard.getClipEntry()?.clipData
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                        nothing = !viewModel.paste(text?.toString().orEmpty())
                    }
                }
            ) {
                Text(stringResource(R.string.setup_paste))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            stringResource(R.string.setup_or_manually),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
    Field(
        location.endpoint,
        { viewModel.location(location.copy(endpoint = it)) },
        R.string.setup_endpoint,
        keyboard = VERBATIM.copy(keyboardType = KeyboardType.Uri),
        error = if (state.invalid) R.string.setup_endpoint_invalid else null,
    )
    Field(
        location.bucket,
        { viewModel.location(location.copy(bucket = it)) },
        R.string.setup_bucket,
    )
    Field(
        location.prefix,
        { viewModel.location(location.copy(prefix = it)) },
        R.string.setup_prefix,
        placeholder = R.string.setup_prefix_hint,
    )
    Field(
        location.accessKey,
        { viewModel.location(location.copy(accessKey = it)) },
        R.string.setup_access_key,
    )
    Secret(
        location.secretKey,
        { viewModel.location(location.copy(secretKey = it)) },
        R.string.setup_secret_key,
        shown,
        { shown = it },
    )
    Field(
        location.region,
        { viewModel.location(location.copy(region = it)) },
        R.string.setup_region,
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.setup_path_style)) },
        supportingContent = { Text(stringResource(R.string.setup_path_style_hint)) },
        trailingContent = { Switch(checked = location.pathStyle, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier.toggleable(
                value = location.pathStyle,
                onValueChange = { viewModel.location(location.copy(pathStyle = it)) },
                role = Role.Switch,
            ),
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    placeholder: Int? = null,
    keyboard: KeyboardOptions = VERBATIM,
    error: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(stringResource(it)) } },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        singleLine = true,
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Hidden until asked for, and kept out of the keyboard's learning. */
@Composable
private fun Secret(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    shown: Boolean,
    onShown: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation =
            if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = VERBATIM.copy(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { onShown(!shown) }) {
                Icon(
                    painterResource(
                        if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                    ),
                    stringResource(if (shown) R.string.hide_secret else R.string.show_secret),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private enum class Mark {
    GOOD,
    BAD,
    NOTE,
    NONE,
}

/** One check, as its own line in plain language (BE-7). */
private data class Line(val mark: Mark, val title: Int, val text: Int)

/** What the checks found, line by line; null while they run. */
private fun lines(verdict: Verdict?): List<Line>? {
    val reachable = Line(Mark.GOOD, R.string.setup_reachable, R.string.setup_reachable_text)
    val credentials = Line(Mark.GOOD, R.string.setup_credentials, R.string.setup_credentials_text)
    val conditional = Line(Mark.GOOD, R.string.setup_conditional, R.string.setup_conditional_text)
    val unchecked = Line(Mark.NONE, R.string.setup_not_checked, 0)
    return when (verdict) {
        null -> null
        Verdict.Accepted -> listOf(reachable, credentials, conditional)
        Verdict.ReadOnly ->
            listOf(
                reachable,
                Line(Mark.NOTE, R.string.setup_read_only, R.string.setup_read_only_text),
                Line(Mark.NONE, R.string.setup_not_checked, R.string.setup_not_checked_read_only),
            )
        Verdict.Refused ->
            listOf(
                reachable,
                credentials,
                Line(Mark.BAD, R.string.setup_unconditional, R.string.setup_unconditional_text),
            )
        is Verdict.Unusable ->
            if (verdict.failure is StoreException.Unreachable) {
                listOf(failure(verdict.failure), unchecked, unchecked)
            } else {
                listOf(reachable, failure(verdict.failure), unchecked)
            }
    }
}

private fun failure(e: StoreException): Line =
    when (e) {
        is StoreException.Unreachable ->
            Line(Mark.BAD, R.string.setup_unreachable, R.string.setup_unreachable_text)
        is StoreException.Denied ->
            Line(Mark.BAD, R.string.setup_denied, R.string.setup_denied_text)
        is StoreException.Missing ->
            Line(Mark.BAD, R.string.setup_missing, R.string.setup_missing_text)
        is StoreException.Failed ->
            Line(Mark.BAD, R.string.setup_failed, R.string.setup_failed_text)
    }

@Composable
private fun Test(state: BackendSetupState) {
    val lines = lines(state.verdict)
    if (lines == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.setup_checking))
        }
        return
    }
    lines.forEach { CheckLine(it) }
}

@Composable
private fun CheckLine(line: Line) {
    val colours = MaterialTheme.colorScheme
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (line.mark == Mark.BAD) colours.errorContainer
                    else colours.surfaceContainerHigh
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp)) {
            val (icon, tint) =
                when (line.mark) {
                    Mark.GOOD -> R.drawable.ic_check to colours.primary
                    Mark.BAD -> R.drawable.ic_close to colours.onErrorContainer
                    Mark.NOTE -> R.drawable.ic_error to colours.error
                    Mark.NONE -> R.drawable.ic_remove to colours.onSurfaceVariant
                }
            Icon(painterResource(icon), null, tint = tint)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(line.title), style = MaterialTheme.typography.titleSmall)
                if (line.text != 0) {
                    Text(stringResource(line.text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun WhatIsThere(state: BackendSetupState) {
    val colours = MaterialTheme.colorScheme
    when (state.contents) {
        Contents.EMPTY -> {
            val blocked =
                when {
                    state.connected -> R.string.setup_empty_connected
                    state.readOnly -> R.string.setup_empty_read_only
                    else -> null
                }
            Found(
                R.drawable.ic_folder,
                R.string.setup_empty,
                listOf(R.string.setup_empty_text, blocked ?: R.string.setup_empty_created),
                if (blocked == null) colours.surfaceContainerHigh else colours.errorContainer,
            )
        }
        Contents.SAME ->
            Found(
                R.drawable.ic_check,
                R.string.setup_same,
                listOf(R.string.setup_same_text),
                colours.secondaryContainer,
            )
        Contents.OLDER,
        Contents.NEWER ->
            Found(
                R.drawable.ic_sync,
                R.string.setup_other_state,
                listOf(
                    if (state.contents == Contents.OLDER) R.string.setup_older_text
                    else R.string.setup_newer_text
                ),
                colours.secondaryContainer,
            )
        Contents.FOREIGN ->
            Found(
                R.drawable.ic_warning,
                R.string.setup_foreign,
                listOf(R.string.setup_foreign_text),
                colours.errorContainer,
            )
        null -> Unit
    }
    if (state.same && state.readOnly) {
        Text(
            stringResource(R.string.setup_read_only_text),
            style = MaterialTheme.typography.bodyMedium,
            color = colours.onSurfaceVariant,
        )
    }
}

@Composable
private fun Found(@DrawableRes icon: Int, title: Int, texts: List<Int>, container: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp)) {
            Icon(painterResource(icon), null)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                texts.forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Encryption(state: BackendSetupState, viewModel: BackendSetupViewModel) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Choice(
            R.drawable.ic_folder,
            R.string.setup_plain,
            R.string.setup_plain_text,
            selected = state.encrypted == false,
            onClick = { viewModel.encrypted(false) },
        )
        Choice(
            R.drawable.ic_lock,
            R.string.setup_encrypted,
            R.string.setup_encrypted_text,
            selected = state.encrypted == true,
            onClick = { viewModel.encrypted(true) },
        )
    }
    Text(
        stringResource(R.string.setup_encryption_final),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Choice(
    @DrawableRes icon: Int,
    title: Int,
    text: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier =
            Modifier.fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null)
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                RadioButton(selected = selected, onClick = null)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColumnScope.Passphrase(state: BackendSetupState, viewModel: BackendSetupViewModel) {
    if (state.custom) Custom(state, viewModel) else Generated(state, viewModel)
    Button(
        onClick = viewModel::next,
        enabled = state.canContinue,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.next))
    }
    if (state.busy) {
        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    }
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

@Composable
private fun Generated(state: BackendSetupState, viewModel: BackendSetupViewModel) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.passphrase)

    Text(stringResource(R.string.passphrase_generated))
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(
                onClick = {
                    // Marked sensitive, so the system does not show it in its copy preview.
                    val clip = ClipData.newPlainText(label, viewModel.generatedText())
                    clip.description.extras =
                        PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(
                    painterResource(R.drawable.ic_content_copy),
                    stringResource(R.string.passphrase_copy),
                )
            }
            state.words.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(
                    value = state.saved,
                    onValueChange = viewModel::saved,
                    role = Role.Checkbox,
                )
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.saved, onCheckedChange = null)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.passphrase_saved))
    }
}

@Composable
private fun Custom(state: BackendSetupState, viewModel: BackendSetupViewModel) {
    var shown by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.passphrase_own_text))
    Secret(state.passphrase, viewModel::passphrase, R.string.passphrase, shown, { shown = it })
    if (state.passphrase.isNotEmpty() && state.strength < 1f) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.passphrase_weak),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    stringResource(R.string.passphrase_weak_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { state.strength },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Confirm(state: BackendSetupState) {
    Spacer(Modifier.height(32.dp))
    Icon(
        painterResource(R.drawable.ic_shield),
        null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally),
    )
    Text(
        stringResource(R.string.setup_confirm_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    if (state.location.plainHttp) {
        Card(
            colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.setup_http_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.setup_http_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Text(
        stringResource(R.string.setup_confirm_text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.setup_confirm_backup),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.busy) {
        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    }
    state.failure?.let { CheckLine(failure(it)) }
}
