package eu.teele.neugesplitter.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.CategoryId
import eu.teele.neugesplitter.domain.value.MemberId

/** A coloured dot, a label and a value at the end, as under a chart. */
@Composable
internal fun Readout(colour: Color?, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (colour != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            Modifier.weight(1f).padding(end = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
    }
}

/** The categories largest first, each as a bar in proportion to the largest (BA-11). */
@Composable
internal fun RankedBars(
    group: Group,
    ranked: List<Pair<CategoryId?, Amount>>,
    modifier: Modifier = Modifier,
) {
    val amounts = group.amounts()
    val largest = ranked.firstOrNull()?.second?.toDouble() ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ranked.forEachIndexed { i, (category, amount) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.emoji(category),
                    Modifier.width(36.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Column(Modifier.weight(1f)) {
                    Readout(null, group.label(category), amounts(amount))
                    Box(
                        Modifier.padding(top = 4.dp)
                            .fillMaxWidth(
                                (amount.toDouble() / largest).toFloat().coerceIn(0.01f, 1f)
                            )
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(seriesColour(i))
                    )
                }
            }
        }
    }
}

/**
 * Who spent how much on what: the categories as rows, largest first and no more than [rows], and
 * the members as columns, each cell as strong as its amount; [detailed] names both and writes the
 * amounts in (BA-12).
 */
@Composable
internal fun MatrixGrid(
    group: Group,
    cells: Map<CategoryId?, Map<MemberId, Amount>>,
    rows: Int,
    detailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val ranked =
        cells.entries
            .map { it.key to it.value }
            .filter { (_, row) -> row.values.any { it.isPositive } }
            .sortedByDescending { (_, row) -> Amount.sum(row.values).units }
            .take(rows)
    val members =
        group.offered().filter { member -> ranked.any { it.second[member.id]?.isPositive == true } }
    val largest = ranked.flatMap { it.second.values }.maxOfOrNull { it.toDouble() } ?: return
    val format = compact()
    val colours = MaterialTheme.colorScheme
    val header = if (detailed) 128.dp else 32.dp
    val cell = 72.dp
    val small = MaterialTheme.typography.labelSmall
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Spacer(Modifier.width(header))
            for (member in members) {
                Text(
                    if (detailed) member.name else member.name.take(1),
                    (if (detailed) Modifier.width(cell) else Modifier.weight(1f)),
                    style = small,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        for ((category, row) in ranked) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (detailed) "${group.emoji(category)} ${group.label(category)}"
                    else group.emoji(category),
                    Modifier.width(header),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                for (member in members) {
                    val value = row[member.id]?.toDouble() ?: 0.0
                    val strength = (value / largest).toFloat().coerceIn(0f, 1f)
                    Box(
                        (if (detailed) Modifier.width(cell) else Modifier.weight(1f))
                            .height(if (detailed) 36.dp else 20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (value > 0) colours.primary.copy(alpha = 0.1f + 0.85f * strength)
                                else colours.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (detailed && value > 0) {
                            Text(
                                format(value),
                                style = small,
                                color =
                                    if (strength > 0.5f) colours.onPrimary else colours.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
