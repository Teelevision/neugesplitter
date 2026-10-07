package eu.teele.neugesplitter.ui.split

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Scheme
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.signed
import java.util.Locale

fun schemeLabel(scheme: Scheme): Int =
    when (scheme) {
        Scheme.EQUAL -> R.string.scheme_equal
        Scheme.SHARES -> R.string.scheme_shares
        Scheme.ADJUSTMENTS -> R.string.scheme_adjustments
        Scheme.ABSOLUTE -> R.string.scheme_absolute
    }

/**
 * How one side of an entry is split, edited live: the parts under the entry follow every keystroke
 * (SP-1 to SP-6, ui-design 5).
 *
 * @param members the members on this side, in the group's order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(
    title: String,
    members: List<Member>,
    split: Split,
    amount: Amount?,
    scale: Scale,
    locale: Locale,
    onScheme: (Scheme) -> Unit,
    onTyped: (MemberId, String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                        onClick = { onScheme(scheme) },
                        text = { Text(stringResource(schemeLabel(scheme)), maxLines = 1) },
                    )
                }
            }
            members.forEachIndexed { index, member ->
                Row(
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        member.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (split.scheme != Scheme.EQUAL) {
                        OutlinedTextField(
                            value = split.input(member.id),
                            onValueChange = { onTyped(member.id, it) },
                            singleLine = true,
                            textStyle =
                                MaterialTheme.typography.bodyLarge
                                    .merge(figures)
                                    .copy(textAlign = TextAlign.End),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        if (split.scheme == Scheme.SHARES) KeyboardType.Number
                                        else KeyboardType.Decimal,
                                    imeAction =
                                        if (index == members.lastIndex) ImeAction.Done
                                        else ImeAction.Next,
                                ),
                            modifier = Modifier.width(120.dp),
                        )
                    }
                    Text(
                        parts?.get(member.id)?.let { part(it, scale, locale) }.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge.merge(figures),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Total(split, order, amount, scale, locale, Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
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
