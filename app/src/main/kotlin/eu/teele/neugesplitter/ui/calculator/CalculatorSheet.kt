package eu.teele.neugesplitter.ui.calculator

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.calculator.Calculation
import eu.teele.neugesplitter.domain.calculator.Fault
import eu.teele.neugesplitter.domain.calculator.Operator
import eu.teele.neugesplitter.domain.calculator.amountIn
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The amount calculator, in place of the keyboard (AM-2 to AM-7, ui-design 5). It starts from
 * [start], and confirming hands [onConfirm] the result rounded to [scale] if [accepts] takes it;
 * the calculation itself is not kept (AM-5). With [onBlank], an empty calculation confirms as
 * nothing. An amount copied elsewhere can be pasted in (AM-9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorSheet(
    start: Amount?,
    scale: Scale,
    onConfirm: (Amount) -> Unit,
    onDismiss: () -> Unit,
    accepts: (Amount) -> Boolean = { it.isPositive },
    onBlank: (() -> Unit)? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    var calculation by
        rememberSaveable(stateSaver = saver(scale)) {
            mutableStateOf(Calculation.start(scale, start))
        }
    var unpasted by remember { mutableStateOf(false) }
    val value = calculation.value?.takeIf(accepts)
    val blank = calculation.result == null && calculation.typed.isEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Display(calculation, locale, unpasted) { text ->
                val amount = amountIn(text, scale)
                unpasted = amount == null
                if (amount != null) calculation = calculation.pasted(amount)
            }
            Keypad(
                calculation,
                locale,
                onChange = {
                    unpasted = false
                    calculation = it
                },
                onConfirm =
                    when {
                        value != null -> {
                            { onConfirm(value) }
                        }
                        blank -> onBlank
                        else -> null
                    },
            )
        }
    }
}

/**
 * The result so far with its operator (AM-3), the number being typed, and any fault (AM-6). Holding
 * the number offers to paste, and [onPaste] takes the clipboard's text (AM-9); [unpasted] tells
 * that it held no amount.
 */
@Composable
private fun Display(
    calculation: Calculation,
    locale: Locale,
    unpasted: Boolean,
    onPaste: (String) -> Unit,
) {
    val result = calculation.result
    val operator = calculation.operator
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    val toolbar = LocalTextToolbar.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val pasting by rememberUpdatedState(onPaste)
    val offer = {
        toolbar.showMenu(
            bounds,
            onPasteRequested = {
                toolbar.hide()
                scope.launch {
                    val clip = clipboard.getClipEntry()?.clipData
                    val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    pasting(item?.text?.toString().orEmpty())
                }
            },
        )
    }
    val paste = stringResource(R.string.calculator_paste)
    // The menu goes with any key pressed, and with the sheet.
    DisposableEffect(calculation) { onDispose { toolbar.hide() } }
    Text(
        if (result != null && operator != null) {
            "${exact(result, calculation.scale, locale)} ${symbol(operator)}"
        } else "",
        style = MaterialTheme.typography.titleLarge.merge(TABULAR),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    // A result standing alone is marked like selected text, as a digit replaces it (AM-4).
    val standing = result != null && operator == null && calculation.typed.isEmpty()
    Text(
        when {
            calculation.typed.isNotEmpty() ->
                AnnotatedString(calculation.typed.replace('.', separator))
            standing ->
                buildAnnotatedString {
                    withStyle(
                        SpanStyle(background = LocalTextSelectionColors.current.backgroundColor)
                    ) {
                        append(exact(result, calculation.scale, locale))
                    }
                }
            else -> AnnotatedString("0")
        },
        style = MaterialTheme.typography.displayMedium.merge(TABULAR),
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier =
            Modifier.fillMaxWidth()
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .pointerInput(Unit) { detectTapGestures(onLongPress = { offer() }) }
                .semantics {
                    onLongClick(paste) {
                        offer()
                        true
                    }
                },
    )
    Text(
        when (calculation.fault) {
            Fault.DIVISION_BY_ZERO -> stringResource(R.string.calculator_division_by_zero)
            Fault.TOO_LARGE -> stringResource(R.string.calculator_too_large)
            null -> if (unpasted) stringResource(R.string.calculator_paste_nothing) else ""
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Digits, the four operators, backspace and clear (AM-2, AM-4); [onConfirm] is null until valid.
 */
@Composable
private fun Keypad(
    calculation: Calculation,
    locale: Locale,
    onChange: (Calculation) -> Unit,
    onConfirm: (() -> Unit)?,
) {
    // Explicit widths, so that the 0 and the confirm key line up with the columns above them.
    BoxWithConstraints {
        val width = (maxWidth - GAP * 3) / 4

        @Composable
        fun Digit(digit: Int, span: Int = 1) =
            Key("$digit", Modifier.width(width * span + GAP * (span - 1)), digit = true) {
                onChange(calculation.digit(digit))
            }

        @Composable
        fun Operation(operator: Operator) =
            Key(symbol(operator), Modifier.width(width), description = description(operator)) {
                onChange(calculation.operator(operator))
            }

        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Key("C", Modifier.width(width), description = R.string.calculator_clear) {
                    onChange(calculation.clear())
                }
                FilledTonalButton(
                    onClick = { onChange(calculation.back()) },
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(width).height(KEY),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_backspace),
                        stringResource(R.string.calculator_back),
                    )
                }
                Operation(Operator.DIVIDE)
                Operation(Operator.TIMES)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Digit(7)
                Digit(8)
                Digit(9)
                Operation(Operator.MINUS)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Digit(4)
                Digit(5)
                Digit(6)
                Operation(Operator.PLUS)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        Digit(1)
                        Digit(2)
                        Digit(3)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        Digit(0, span = 2)
                        Key(
                            "${DecimalFormatSymbols.getInstance(locale).decimalSeparator}",
                            Modifier.width(width),
                            digit = true,
                            enabled = calculation.scale.digits > 0,
                        ) {
                            onChange(calculation.point())
                        }
                    }
                }
                Button(
                    onClick = { onConfirm?.invoke() },
                    enabled = onConfirm != null,
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(width).height(KEY * 2 + GAP),
                ) {
                    Icon(painterResource(R.drawable.ic_check), stringResource(R.string.ok))
                }
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    modifier: Modifier,
    digit: Boolean = false,
    @StringRes description: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val spoken = description?.let { stringResource(it) }
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(0.dp),
        colors =
            if (digit) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            } else ButtonDefaults.filledTonalButtonColors(),
        modifier =
            modifier.height(KEY).semantics { if (spoken != null) contentDescription = spoken },
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

private fun symbol(operator: Operator): String =
    when (operator) {
        Operator.PLUS -> "+"
        Operator.MINUS -> "−"
        Operator.TIMES -> "×"
        Operator.DIVIDE -> "÷"
    }

@StringRes
private fun description(operator: Operator): Int =
    when (operator) {
        Operator.PLUS -> R.string.calculator_plus
        Operator.MINUS -> R.string.calculator_minus
        Operator.TIMES -> R.string.calculator_times
        Operator.DIVIDE -> R.string.calculator_divide
    }

/** A result so far, exact to 10⁻⁸ and with at least the group's decimals; display only (TR-34). */
private fun exact(amount: Amount, scale: Scale, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.minimumFractionDigits = scale.digits
    format.maximumFractionDigits = Amount.UNIT_DIGITS
    return format.format(BigDecimal.valueOf(amount.units, Amount.UNIT_DIGITS))
}

/** Keeps a calculation across configuration changes, as result units, operator and typed text. */
private fun saver(scale: Scale) =
    Saver<Calculation, String>(
        save = { "${it.result?.units ?: ""}|${it.operator?.name ?: ""}|${it.typed}" },
        restore = {
            val (result, operator, typed) = it.split('|')
            Calculation(
                scale,
                result.toLongOrNull()?.let(Amount::ofUnits),
                operator.takeIf(String::isNotEmpty)?.let(Operator::valueOf),
                typed,
            )
        },
    )

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

private val KEY = 64.dp

private val GAP = 8.dp
