package eu.teele.neugesplitter.ui.calculator

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.calculator.Calculation
import eu.teele.neugesplitter.domain.calculator.Fault
import eu.teele.neugesplitter.domain.calculator.Operator
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * The amount calculator, in place of the keyboard (AM-2 to AM-7, ui-design 5). It starts from
 * [start], and confirming hands [onConfirm] the result rounded to [scale] if [accepts] takes it;
 * the calculation itself is not kept (AM-5). With [onBlank], an empty calculation confirms as
 * nothing.
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
            Display(calculation, locale)
            Keypad(
                calculation,
                locale,
                onChange = { calculation = it },
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

/** The result so far with its operator (AM-3), the number being typed, and any fault (AM-6). */
@Composable
private fun Display(calculation: Calculation, locale: Locale) {
    val result = calculation.result
    val operator = calculation.operator
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
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
    Text(
        when {
            calculation.typed.isNotEmpty() -> calculation.typed.replace('.', separator)
            result != null && operator == null -> exact(result, calculation.scale, locale)
            else -> "0"
        },
        style = MaterialTheme.typography.displayMedium.merge(TABULAR),
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        when (calculation.fault) {
            Fault.DIVISION_BY_ZERO -> stringResource(R.string.calculator_division_by_zero)
            Fault.TOO_LARGE -> stringResource(R.string.calculator_too_large)
            null -> ""
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
