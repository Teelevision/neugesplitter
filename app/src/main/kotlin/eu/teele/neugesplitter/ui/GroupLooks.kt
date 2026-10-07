package eu.teele.neugesplitter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.ui.theme.GROUP_COLOURS
import eu.teele.neugesplitter.ui.theme.groupColour

private val EMOJI = listOf("🏠", "✈️", "🏕️", "❤️", "🎉")

private val COLOUR_NAMES =
    listOf(
        R.string.colour_green,
        R.string.colour_teal,
        R.string.colour_blue,
        R.string.colour_violet,
        R.string.colour_berry,
        R.string.colour_ochre,
    )

/** The emoji a group can be given; [onSelect] is told the one tapped, chosen or not (GR-2). */
@Composable
internal fun EmojiPicker(selected: String?, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (emoji in EMOJI) {
            val chosen = selected == emoji
            Box(
                modifier =
                    Modifier.size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (chosen) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .border(
                            if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else BorderStroke(0.dp, Color.Transparent),
                            RoundedCornerShape(12.dp),
                        )
                        .toggleable(value = chosen, onValueChange = { onSelect(emoji) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

/** The colours a group can be given (GR-2). */
@Composable
internal fun ColourPicker(selected: Colour?, onSelect: (Colour) -> Unit) {
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GROUP_COLOURS.forEachIndexed { index, colour ->
            val chosen = selected == colour
            val name = stringResource(COLOUR_NAMES[index])
            Box(
                modifier =
                    Modifier.size(44.dp)
                        .border(
                            if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
                            else BorderStroke(0.dp, Color.Transparent),
                            CircleShape,
                        )
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(groupColour(colour))
                        .selectable(
                            selected = chosen,
                            onClick = { onSelect(colour) },
                            role = Role.RadioButton,
                        )
                        .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                if (chosen) {
                    Icon(painterResource(R.drawable.ic_check), null, tint = Color.White)
                }
            }
        }
    }
}
