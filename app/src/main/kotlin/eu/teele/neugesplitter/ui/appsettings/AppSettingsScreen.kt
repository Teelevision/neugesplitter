package eu.teele.neugesplitter.ui.appsettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.Theme
import kotlinx.coroutines.launch

private enum class Dialog {
    THEME,
    LANGUAGE,
}

/**
 * The app's theme and language, what it is and is built with, and how everything it holds is
 * removed from this device (ui-design 3, UX-3, UX-5, PR-9).
 *
 * @param version the app's version name.
 * @param onDeleted called once everything is gone from this device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel,
    version: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var licences by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val confirmation = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)

    // The keyboard shrinks the viewport while it animates in, so ask again at every step.
    LaunchedEffect(deleting, keyboard) { if (deleting) confirmation.bringIntoView() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.destination_settings)) },
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
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)
        ) {
            SettingRow(
                stringResource(R.string.theme),
                stringResource(theme.label),
                onClick = { dialog = Dialog.THEME },
            )

            SettingRow(
                stringResource(R.string.language),
                stringResource(language.label),
                onClick = { dialog = Dialog.LANGUAGE },
            )

            SettingRow(stringResource(R.string.version), version)
            SettingRow(stringResource(R.string.licence), stringResource(R.string.licence_notice))

            SettingRow(
                stringResource(R.string.delete_all),
                supporting = null,
                onClick = { deleting = !deleting },
                headlineColour = MaterialTheme.colorScheme.error,
            )
            if (deleting) {
                val word = stringResource(R.string.delete_all_word)
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .bringIntoViewRequester(confirmation)
                            .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.delete_all_warning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(R.string.delete_all_prompt, word)) },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Text,
                            ),
                        modifier =
                            Modifier.fillMaxWidth().onFocusChanged {
                                if (it.isFocused) scope.launch { confirmation.bringIntoView() }
                            },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                deleting = false
                                typed = ""
                            }
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = { viewModel.deleteAll(onDeleted) },
                            enabled = typed.trim().equals(word, ignoreCase = true),
                        ) {
                            Text(
                                stringResource(R.string.delete_all_confirm),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            SettingRow(
                stringResource(R.string.third_party),
                supporting = null,
                onClick = { licences = !licences },
            )
            if (licences) {
                val adapted = stringResource(R.string.adapted)
                SettingRow(
                    stringResource(R.string.words_german),
                    "Uli Fouquet · CC BY 3.0 · $adapted",
                )
                SettingRow(
                    stringResource(R.string.words_english),
                    "Electronic Frontier Foundation · CC BY 3.0 US · $adapted",
                )
                for ((library, licence) in LIBRARIES) SettingRow(library, licence)
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        Dialog.THEME ->
            ChoiceDialog(
                title = R.string.theme,
                choices = Theme.entries.map { it to stringResource(it.label) },
                selected = theme,
                onChoose = {
                    viewModel.theme(it)
                    close()
                },
                onCancel = close,
            )
        Dialog.LANGUAGE ->
            ChoiceDialog(
                title = R.string.language,
                choices = LANGUAGES.map { it to stringResource(it.label) },
                selected = language,
                onChoose = {
                    if (it != language) viewModel.language(it)
                    close()
                },
                onCancel = close,
            )
        null -> Unit
    }
}

/** The language tags offered, the system's (null) first. */
private val LANGUAGES = listOf(null, "en", "de")

/** What the release build ships, by its runtime classpath. */
private val LIBRARIES =
    listOf(
        "AndroidX, Jetpack Compose" to "Apache-2.0",
        "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache-2.0",
        "SQLDelight" to "Apache-2.0",
        "OkHttp, Okio" to "Apache-2.0",
        "kage" to "MIT, Apache-2.0",
        "Bouncy Castle" to "MIT",
        "HKDF" to "Apache-2.0",
        "kotlin-result" to "ISC",
        "zxcvbn4j" to "MIT",
        "Guava ListenableFuture" to "Apache-2.0",
        "JSpecify" to "Apache-2.0",
    )

private val Theme.label: Int
    get() =
        when (this) {
            Theme.SYSTEM -> R.string.system_default
            Theme.LIGHT -> R.string.theme_light
            Theme.DARK -> R.string.theme_dark
        }

private val String?.label: Int
    get() =
        when (this) {
            "en" -> R.string.language_english
            "de" -> R.string.language_german
            else -> R.string.system_default
        }

/** A label and its value; a row with no [onClick] only informs. */
@Composable
private fun SettingRow(
    headline: String,
    supporting: String?,
    onClick: (() -> Unit)? = null,
    headlineColour: Color = Color.Unspecified,
) {
    ListItem(
        headlineContent = { Text(headline, color = headlineColour) },
        supportingContent = supporting?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
    )
}

/** One radio row per choice; tapping one chooses it. */
@Composable
private fun <T> ChoiceDialog(
    title: Int,
    choices: List<Pair<T, String>>,
    selected: T,
    onChoose: (T) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                for ((value, label) in choices) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = value == selected,
                                    onClick = { onChoose(value) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
