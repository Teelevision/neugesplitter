package eu.teele.neugesplitter.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale

/** "You are owed 64.20", with the amount in bold tabular figures (BA-7, ui-design 9). */
@Composable
fun position(own: Amount, scale: Scale): AnnotatedString {
    if (own.isZero) return AnnotatedString(stringResource(R.string.group_settled))
    val amount = formatted(own, scale, LocalConfiguration.current.locales[0])
    val text =
        stringResource(if (own.isPositive) R.string.group_owed else R.string.group_owe, amount)
    val start = text.indexOf(amount)
    return buildAnnotatedString {
        append(text)
        if (start >= 0) {
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                start,
                start + amount.length,
            )
        }
    }
}
