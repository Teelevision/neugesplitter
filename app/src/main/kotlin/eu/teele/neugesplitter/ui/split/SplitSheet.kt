package eu.teele.neugesplitter.ui.split

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Scheme
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.ui.calculator.CalculatorSheet
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.signed
import java.text.DecimalFormatSymbols
import java.util.Locale

fun schemeLabel(scheme: Scheme): Int =
    when (scheme) {
        Scheme.EQUAL -> R.string.scheme_equal
        Scheme.SHARES -> R.string.scheme_shares
        Scheme.ADJUSTMENTS -> R.string.scheme_adjustments
        Scheme.ABSOLUTE -> R.string.scheme_absolute
    }

/**
 * How one side of an entry is split, edited as a draft that starts from [start]: only OK hands it
 * to [onDone], and leaving the sheet any other way drops it (SP-1 to SP-6, ui-design 5).
 *
 * @param members the members on this side, in the group's order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(
    title: String,
    members: List<Member>,
    start: Split,
    amount: Amount?,
    scale: Scale,
    locale: Locale,
    onDone: (Split) -> Unit,
    onDismiss: () -> Unit,
) {
    var split by remember { mutableStateOf(start) }
    var calculating by remember { mutableStateOf<MemberId?>(null) }
    val onTyped = { member: MemberId, text: String ->
        if (split.accepts(member, text, scale)) split = split.typed(member, text)
    }
    val order = members.map { it.id }
    val parts = amount?.let { split.parts(order, it) }
    val figures = TextStyle(fontFeatureSettings = "tnum")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.split_title, title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            PrimaryTabRow(
                selectedTabIndex = split.scheme.ordinal,
                containerColor = Color.Transparent,
            ) {
                for (scheme in Scheme.entries) {
                    Tab(
                        selected = scheme == split.scheme,
                        onClick = { split = split.copy(scheme = scheme) },
                        text = { Text(stringResource(schemeLabel(scheme)), maxLines = 1) },
                    )
                }
            }
            members.forEachIndexed { index, member ->
                val stepping = split.scheme == Scheme.SHARES
                val imeAction = if (index == members.lastIndex) ImeAction.Done else ImeAction.Next
                Row(
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    // The stepper takes room the names and parts would otherwise have on 360dp.
                    horizontalArrangement = Arrangement.spacedBy(if (stepping) 8.dp else 16.dp),
                ) {
                    Text(
                        member.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (stepping) {
                        SharesStepper(
                            member = member,
                            value = split.input(member.id),
                            imeAction = imeAction,
                            onChange = { onTyped(member.id, it) },
                        )
                    } else if (split.scheme != Scheme.EQUAL) {
                        CalculatorField(
                            split.input(member.id),
                            stringResource(R.string.split_value, member.name),
                            figures,
                            onOpen = { calculating = member.id },
                        )
                    }
                    Text(
                        parts?.get(member.id)?.let { part(it, scale, locale) }.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge.merge(figures),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(if (stepping) 80.dp else 96.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Total(split, order, amount, scale, locale, Modifier.weight(1f))
                TextButton(onClick = { onDone(split) }) { Text(stringResource(R.string.ok)) }
            }
        }
    }
    calculating?.let { member ->
        val input = split.input(member)
        val close = { text: String ->
            onTyped(member, text)
            calculating = null
        }
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        CalculatorSheet(
            start = input.takeIf { it.isNotEmpty() }?.let(Split::amount),
            scale = scale,
            onConfirm = { close(Split.text(it, scale, separator)) },
            onDismiss = { calculating = null },
            // Only adjustments may be negative (SP-4).
            accepts = { split.scheme == Scheme.ADJUSTMENTS || !it.isNegative },
            onBlank = { close("") },
        )
    }
}

/** An amount the calculator fills in: a tap opens it rather than the keyboard (AM-2). */
@Composable
private fun CalculatorField(value: String, label: String, figures: TextStyle, onOpen: () -> Unit) {
    val taps = remember { MutableInteractionSource() }
    LaunchedEffect(taps) {
        taps.interactions.collect { if (it is PressInteraction.Release) onOpen() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        textStyle =
            MaterialTheme.typography.bodyLarge.merge(figures).copy(textAlign = TextAlign.End),
        interactionSource = taps,
        modifier = Modifier.width(120.dp).semantics { contentDescription = label },
    )
}

/** [member]'s shares: a number to type over, with a button each side to add or take one. */
@Composable
private fun SharesStepper(
    member: Member,
    value: String,
    imeAction: ImeAction,
    onChange: (String) -> Unit,
) {
    // Typing may be refused, then the field shows [value] again.
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    val shares = value.toLongOrNull() ?: 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((shares - 1).toString()) }, enabled = shares > 0) {
            Icon(
                painterResource(R.drawable.ic_minus),
                stringResource(R.string.split_shares_fewer, member.name),
            )
        }
        OutlinedTextField(
            value = shown,
            onValueChange = {
                field = it
                if (it.text != value) onChange(it.text)
            },
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodyLarge
                    .merge(TextStyle(fontFeatureSettings = "tnum"))
                    .copy(textAlign = TextAlign.Center),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
            modifier =
                Modifier.width(64.dp).onFocusChanged {
                    if (it.isFocused) {
                        field = TextFieldValue(shown.text, TextRange(0, shown.text.length))
                    }
                },
        )
        IconButton(onClick = { onChange((shares + 1).toString()) }) {
            Icon(
                painterResource(R.drawable.ic_add),
                stringResource(R.string.split_shares_more, member.name),
            )
        }
    }
}

/** How far the inputs are from adding up, so the user can see what is left to fix (SP-6). */
@Composable
private fun Total(
    split: Split,
    order: List<MemberId>,
    amount: Amount?,
    scale: Scale,
    locale: Locale,
    modifier: Modifier,
) {
    val mismatch = amount?.let { split.mismatch(order, it) }
    val off = split.off(order, amount ?: Amount.ZERO)
    val text =
        when {
            split.scheme == Scheme.EQUAL -> null
            mismatch == Mismatch.UNREADABLE -> stringResource(R.string.split_unreadable)
            split.scheme == Scheme.SHARES ->
                stringResource(
                    R.string.split_shares_total,
                    order.sumOf { split.input(it).toLongOrNull() ?: 0L }.toString(),
                )
            split.scheme == Scheme.ADJUSTMENTS && off != null ->
                stringResource(R.string.split_adjustments_sum, signed(off, scale, locale))
            amount == null || off == null -> null
            off.isPositive ->
                stringResource(R.string.split_left, formatted(off.roundedTo(scale), scale, locale))
            off.isNegative ->
                stringResource(R.string.split_over, formatted(off.roundedTo(scale), scale, locale))
            else -> stringResource(R.string.split_adds_up)
        }
    Text(
        text.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (mismatch != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A part at [scale]; a negative one, which only adjustments can make, keeps its sign. */
fun part(amount: Amount, scale: Scale, locale: Locale): String {
    val rounded = amount.roundedTo(scale)
    return if (rounded.isNegative) signed(rounded, scale, locale)
    else formatted(rounded, scale, locale)
}
