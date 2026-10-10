package eu.teele.neugesplitter.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.entry.Scheme
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.recurrence.Mode
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.ui.calculator.CalculatorSheet
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.sideLabels
import eu.teele.neugesplitter.ui.split.Mismatch
import eu.teele.neugesplitter.ui.split.SplitSheet
import eu.teele.neugesplitter.ui.split.part
import eu.teele.neugesplitter.ui.split.schemeLabel
import eu.teele.neugesplitter.ui.theme.largeText
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
    var splitting by rememberSaveable { mutableStateOf<Party?>(null) }
    // The scheme the sheet opens with; the side keeps its own until OK.
    var splitScheme by rememberSaveable { mutableStateOf(Scheme.EQUAL) }
    var recurring by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var describe by remember { mutableStateOf(false) }
    val label = { party: Party -> if (party == Party.PAYERS) payersLabel else beneficiariesLabel }
    val focus = LocalFocusManager.current
    val description = remember { FocusRequester() }
    val amountTaps = remember { MutableInteractionSource() }

    LaunchedEffect(state.done) { if (state.done) onClose() }
    // Amount first: a blank new entry asks for it right away, once.
    LaunchedEffect(state.loaded) {
        if (state.loaded && state.blank && !asked) {
            asked = true
            calculating = true
        }
    }
    // Waits for the calculator to be gone, or the keyboard would not show.
    LaunchedEffect(describe, calculating) {
        if (describe && !calculating) {
            describe = false
            description.requestFocus()
        }
    }
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
                                    Problem.PAYER_SPLIT ->
                                        splitProblem(
                                            state,
                                            Party.PAYERS,
                                            label(Party.PAYERS),
                                            locale,
                                        )
                                    Problem.BENEFICIARY_SPLIT ->
                                        splitProblem(
                                            state,
                                            Party.BENEFICIARIES,
                                            label(Party.BENEFICIARIES),
                                            locale,
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
                    Button(onClick = { viewModel.save() }, enabled = state.canSave) {
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
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::description,
                label = { Text(stringResource(R.string.entry_description)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(description),
            )
            Section(stringResource(R.string.entry_date)) {
                OutlinedButton(onClick = { picking = true }) { Text(longDate(state.date, locale)) }
            }
            for (party in Party.entries) {
                Section(stringResource(label(party))) {
                    Chooser(
                        state.members,
                        state.selected(party),
                        if (party == Party.PAYERS) viewModel::payer else viewModel::beneficiary,
                    )
                    val open = { scheme: Scheme ->
                        splitScheme = scheme
                        splitting = party
                    }
                    SplitSummary(
                        state,
                        party,
                        locale,
                        onScheme = {
                            if (it == Scheme.EQUAL) viewModel.scheme(party, it) else open(it)
                        },
                        onOpen = { open(state.split(party).scheme) },
                    )
                }
            }
            Repeats(
                state,
                locale,
                onSwitch = { on ->
                    when {
                        !on -> viewModel.recurrence(null)
                        // It can only go back to repeating as it did.
                        state.fixed -> viewModel.recurrence(state.was)
                        else -> recurring = true
                    }
                },
                onOpen = { recurring = true },
            )
        }
    }

    if (recurring) {
        RecurrenceSheet(
            date = state.date,
            choosesStart = !state.occurrence,
            initial = state.recurrence ?: state.was ?: Recurrence.on(state.date),
            locale = locale,
            onStart = viewModel::date,
            onDone = {
                viewModel.recurrence(it)
                recurring = false
            },
            onDismiss = { recurring = false },
        )
    }
    if (state.asking) {
        Following(
            fixed = state.fixed,
            onAnswer = { viewModel.save(following = it) },
            onDismiss = viewModel::dismiss,
        )
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
                if (state.description.isEmpty()) describe = true
            },
            onDismiss = { calculating = false },
        )
    }
    splitting?.let { party ->
        val order = state.ordered(party)
        // Down to one member, there is nothing left to split (SP-1).
        if (order.size < 2) {
            LaunchedEffect(Unit) { splitting = null }
            return@let
        }
        val names = state.members.associateBy { it.id }
        SplitSheet(
            title = stringResource(label(party)),
            members = order.mapNotNull { names[it] },
            start = state.split(party).copy(scheme = splitScheme),
            amount = state.amount,
            scale = state.scale,
            locale = locale,
            onDone = {
                viewModel.split(party, it)
                splitting = null
            },
            onDismiss = { splitting = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Kinds(selected: Kind, onSelect: (Kind) -> Unit) =
    Segments(Kind.entries, selected, ::kindLabel, onSelect)

/**
 * The look shared by the kind bar and the split bar. [compact] drops the check mark and the label
 * padding, which on 360dp leave four segments too little room for a word like "Angepasst". At a
 * large text size no segment has room for its word, so the options become chips that wrap.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun <T> Segments(
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    compact: Boolean = false,
) {
    if (largeText()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(stringResource(label(option))) },
                    leadingIcon =
                        if (option == selected) {
                            {
                                Icon(
                                    painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                )
                            }
                        } else null,
                )
            }
        }
        return
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val shape = SegmentedButtonDefaults.itemShape(index, options.size)
            val text = @Composable { Text(stringResource(label(option)), maxLines = 1) }
            if (compact) {
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = shape,
                    contentPadding = PaddingValues(),
                    icon = {},
                    label = text,
                )
            } else {
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = shape,
                    label = text,
                )
            }
        }
    }
}

/**
 * Whether the entry repeats and how. Switching it on opens the recurrence sheet, and so does a tap
 * on the rule (RE-1). An occurrence switched off says that its series ends after it (RE-7). A
 * series fixed by later decisions keeps its rule, and says why (RE-6).
 */
@Composable
private fun Repeats(
    state: EntryEditorState,
    locale: Locale,
    onSwitch: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val recurrence = state.recurrence
    val label = stringResource(R.string.repeats)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = recurrence != null && !state.fixed, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rule =
                when {
                    recurrence != null ->
                        listOfNotNull(
                                recurrenceText(recurrence.interval, locale),
                                stringResource(modeLabel(Mode.SUGGESTED)).takeIf {
                                    recurrence.mode == Mode.SUGGESTED
                                },
                            )
                            .joinToString(" · ")
                    state.was != null -> stringResource(R.string.repeats_ends_here)
                    else -> null
                }
            rule?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (recurrence != null && state.fixed) {
                Text(
                    stringResource(R.string.series_fixed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = recurrence != null,
            onCheckedChange = onSwitch,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * Whether a change to an occurrence goes for the rest of its series too (RE-6); not if the series
 * is [fixed] by later decisions.
 */
@Composable
private fun Following(fixed: Boolean, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var following by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.following_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                for (option in listOf(false, true)) {
                    val enabled = !(option && fixed)
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = following == option,
                                    enabled = enabled,
                                    onClick = { following = option },
                                    role = Role.RadioButton,
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = following == option,
                            onClick = null,
                            enabled = enabled,
                        )
                        Text(
                            stringResource(
                                if (option) R.string.following_too else R.string.following_only
                            ),
                            color =
                                if (enabled) Color.Unspecified
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
                if (fixed) {
                    Text(
                        stringResource(R.string.series_fixed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(following) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
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

/**
 * With more than one member on [party]'s side: how it is split, chosen on a bar, and what each part
 * comes to, which opens the split sheet unless the split is equal (SP-1, SP-6).
 */
@Composable
private fun SplitSummary(
    state: EntryEditorState,
    party: Party,
    locale: Locale,
    onScheme: (Scheme) -> Unit,
    onOpen: () -> Unit,
) {
    val order = state.ordered(party)
    if (order.size < 2) return
    val split = state.split(party)
    Segments(Scheme.entries, split.scheme, ::schemeLabel, onScheme, compact = true)
    val amount = state.amount ?: return
    val parts = split.parts(order, amount) ?: return
    val names = state.members.associate { it.id to it.name }
    Text(
        parts.entries.joinToString("   ") { (member, part) ->
            "${names[member]} ${part(part, state.scale, locale)}"
        },
        style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(enabled = split.scheme != Scheme.EQUAL, onClick = onOpen),
    )
}

/** Why [party]'s split keeps the entry from being saved (SP-6). */
@Composable
private fun splitProblem(
    state: EntryEditorState,
    party: Party,
    label: Int,
    locale: Locale,
): String? {
    val side = stringResource(label)
    val off = state.amount?.let { state.split(party).off(state.ordered(party), it) }
    val shown = { amount: Amount -> formatted(amount.roundedTo(state.scale), state.scale, locale) }
    return when (state.mismatch(party)) {
        Mismatch.UNREADABLE -> stringResource(R.string.problem_split_unreadable, side)
        Mismatch.NO_SHARES -> stringResource(R.string.problem_split_no_shares, side)
        Mismatch.ADJUSTMENTS_OVER -> stringResource(R.string.problem_split_adjustments, side)
        Mismatch.ABSOLUTE_OFF ->
            off?.let {
                stringResource(
                    if (it.isPositive) R.string.problem_split_left else R.string.problem_split_over,
                    side,
                    shown(it),
                )
            }
        null -> null
    }
}

/** Picks a date, among those [selectable] allows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePick(
    date: EntryDate,
    onPicked: (EntryDate) -> Unit,
    onDismiss: () -> Unit,
    selectable: (EntryDate) -> Boolean = { true },
) {
    val picker =
        rememberDatePickerState(
            initialSelectedDateMillis =
                date.value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) =
                        selectable(
                            EntryDate.of(
                                Instant.ofEpochMilli(utcTimeMillis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                            )
                        )
                },
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
