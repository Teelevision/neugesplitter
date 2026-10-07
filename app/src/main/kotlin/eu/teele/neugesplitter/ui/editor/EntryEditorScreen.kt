package eu.teele.neugesplitter.ui.editor

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Side
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.ui.calculator.CalculatorSheet
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.sideLabels
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** One screen for all three kinds of entry, new or edited (EN-1, ui-design 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorScreen(viewModel: EntryEditorViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val (payersLabel, beneficiariesLabel) = sideLabels(state.kind)
    var picking by rememberSaveable { mutableStateOf(false) }
    var calculating by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val description = remember { FocusRequester() }
    val amountTaps = remember { MutableInteractionSource() }

    LaunchedEffect(state.done) { if (state.done) onClose() }
    LaunchedEffect(state.loaded) { if (state.loaded && !state.editing) description.requestFocus() }
    LaunchedEffect(amountTaps) {
        amountTaps.interactions.collect {
            if (it is PressInteraction.Release) {
                focus.clearFocus()
                calculating = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.editing) R.string.edit_entry else R.string.new_entry
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val reason =
                        when {
                            state.failed -> stringResource(R.string.save_failed)
                            !state.loaded -> null
                            else ->
                                when (state.problem) {
                                    Problem.DESCRIPTION ->
                                        stringResource(R.string.problem_description)
                                    Problem.AMOUNT -> stringResource(R.string.problem_amount)
                                    Problem.PAYERS ->
                                        stringResource(
                                            R.string.problem_side,
                                            stringResource(payersLabel),
                                        )
                                    Problem.BENEFICIARIES ->
                                        stringResource(
                                            R.string.problem_side,
                                            stringResource(beneficiariesLabel),
                                        )
                                    null -> null
                                }
                        }
                    Text(
                        reason.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (state.failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = viewModel::save, enabled = state.canSave) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
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
            Kinds(state.kind, viewModel::kind)
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::description,
                label = { Text(stringResource(R.string.entry_description)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onNext = {
                            focus.clearFocus()
                            calculating = true
                        }
                    ),
                modifier = Modifier.fillMaxWidth().focusRequester(description),
            )
            // Read-only: tapping it opens the calculator instead of the keyboard (AM-2).
            OutlinedTextField(
                value = state.amount?.let { formatted(it, state.scale, locale) }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.entry_amount)) },
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.headlineSmall.merge(
                        TextStyle(fontFeatureSettings = "tnum")
                    ),
                interactionSource = amountTaps,
                modifier = Modifier.fillMaxWidth(),
            )
            Section(stringResource(R.string.entry_date)) {
                OutlinedButton(onClick = { picking = true }) { Text(longDate(state.date, locale)) }
            }
            Section(stringResource(payersLabel)) {
                Chooser(state.members, state.payers, viewModel::payer)
                Parts(state.members, state.payerSide, state.amount, state.scale, locale)
            }
            Section(stringResource(beneficiariesLabel)) {
                Chooser(state.members, state.beneficiaries, viewModel::beneficiary)
                Parts(state.members, state.beneficiarySide, state.amount, state.scale, locale)
            }
        }
    }

    if (picking) {
        DatePick(
            date = state.date,
            onPicked = {
                viewModel.date(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
    if (calculating) {
        CalculatorSheet(
            start = state.amount,
            scale = state.scale,
            onConfirm = {
                viewModel.amount(it)
                calculating = false
            },
            onDismiss = { calculating = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Kinds(selected: Kind, onSelect: (Kind) -> Unit) {
    val kinds = Kind.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
            ) {
                Text(stringResource(kindLabel(kind)))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chooser(members: List<Member>, selected: Set<MemberId>, onToggle: (MemberId) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (member in members) {
            FilterChip(
                selected = member.id in selected,
                onClick = { onToggle(member.id) },
                label = { Text(member.name) },
            )
        }
    }
}

/** What each member's part comes to, while there is more than one of them (SP-6). */
@Composable
private fun Parts(
    members: List<Member>,
    side: Side?,
    amount: Amount?,
    scale: Scale,
    locale: Locale,
) {
    if (side == null || amount == null || side.members.size < 2) return
    val parts = runCatching { side.parts(amount) }.getOrNull() ?: return
    val names = members.associate { it.id to it.name }
    Text(
        parts.entries.joinToString("   ") { (member, part) ->
            "${names[member]} ${formatted(part.roundedTo(scale), scale, locale)}"
        },
        style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePick(date: EntryDate, onPicked: (EntryDate) -> Unit, onDismiss: () -> Unit) {
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis =
                date.value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    picker.selectedDateMillis?.let {
                        onPicked(
                            EntryDate.of(
                                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            )
                        )
                    } ?: onDismiss()
                }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = picker)
    }
}
