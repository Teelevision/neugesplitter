package eu.teele.neugesplitter.ui.overview

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.calculator.amountIn
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.ui.editor.DatePick
import eu.teele.neugesplitter.ui.kindIcon
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.sideLabels
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Narrows the overview's entries as they are being chosen (EN-12): by a part of the description,
 * the amount, the kind, who paid, whom it was for, and the date.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheet(
    filter: EntryFilter,
    members: Map<MemberId, String>,
    scale: Scale,
    today: EntryDate,
    onChange: (EntryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val (payersLabel, beneficiariesLabel) = sideLabels(filter.kind ?: Kind.EXPENSE)
    var picking by rememberSaveable { mutableStateOf<Boolean?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.filter),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange(EntryFilter()) }, enabled = filter.active) {
                    Text(stringResource(R.string.filter_clear))
                }
            }
            OutlinedTextField(
                value = filter.text,
                onValueChange = { onChange(filter.copy(text = it)) },
                label = { Text(stringResource(R.string.filter_text)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AmountField(filter.least, scale, R.string.filter_least, Modifier.weight(1f)) {
                    onChange(filter.copy(least = it))
                }
                AmountField(filter.most, scale, R.string.filter_most, Modifier.weight(1f)) {
                    onChange(filter.copy(most = it))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in listOf(Kind.EXPENSE, Kind.INCOME, Kind.TRANSFER)) {
                    FilterChip(
                        selected = filter.kind == kind,
                        onClick = {
                            onChange(filter.copy(kind = kind.takeUnless { filter.kind == kind }))
                        },
                        label = { Text(stringResource(kindLabel(kind))) },
                        leadingIcon = {
                            Icon(painterResource(kindIcon(kind)), null, Modifier.size(18.dp))
                        },
                    )
                }
            }
            Members(stringResource(payersLabel), members, filter.payers) {
                onChange(filter.copy(payers = it))
            }
            Members(stringResource(beneficiariesLabel), members, filter.beneficiaries) {
                onChange(filter.copy(beneficiaries = it))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateChip(
                    filter.from?.let {
                        stringResource(R.string.filter_from, dates.format(it.value))
                    } ?: stringResource(R.string.filter_from_any),
                    set = filter.from != null,
                    onPick = { picking = true },
                    onClear = { onChange(filter.copy(from = null)) },
                )
                DateChip(
                    filter.until?.let {
                        stringResource(R.string.filter_until, dates.format(it.value))
                    } ?: stringResource(R.string.filter_until_any),
                    set = filter.until != null,
                    onPick = { picking = false },
                    onClear = { onChange(filter.copy(until = null)) },
                )
            }
        }
    }
    picking?.let { from ->
        DatePick(
            date = (if (from) filter.from else filter.until) ?: today,
            onPicked = {
                onChange(if (from) filter.copy(from = it) else filter.copy(until = it))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun AmountField(
    value: String,
    scale: Scale,
    @StringRes label: Int,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    val unreadable = value.isNotBlank() && amountIn(value, scale) == null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = unreadable,
        supportingText =
            if (unreadable) {
                { Text(stringResource(R.string.filter_unreadable)) }
            } else null,
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = modifier,
    )
}

/** Members to choose of, each of whom must be on that side (EN-12). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Members(
    label: String,
    members: Map<MemberId, String>,
    chosen: Set<MemberId>,
    onChange: (Set<MemberId>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, name) in members) {
                FilterChip(
                    selected = id in chosen,
                    onClick = { onChange(if (id in chosen) chosen - id else chosen + id) },
                    label = { Text(name) },
                )
            }
        }
    }
}

@Composable
private fun DateChip(label: String, set: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    FilterChip(
        selected = set,
        onClick = onPick,
        label = { Text(label) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_event), null, Modifier.size(18.dp)) },
        trailingIcon =
            if (set) {
                {
                    IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            stringResource(R.string.filter_clear),
                            Modifier.size(18.dp),
                        )
                    }
                }
            } else null,
    )
}
