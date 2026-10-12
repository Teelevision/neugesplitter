package eu.teele.neugesplitter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.ui.theme.GROUP_COLOURS
import eu.teele.neugesplitter.ui.theme.groupColour
import eu.teele.neugesplitter.ui.theme.onGroupColour
import java.text.BreakIterator

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

/** The first character of [text] as a reader sees it, an emoji with all its parts; null if none. */
internal fun firstGrapheme(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val characters = BreakIterator.getCharacterInstance()
    characters.setText(trimmed)
    return trimmed.substring(0, characters.next())
}

/**
 * The emoji a group can be given: five to tap, and any other typed in place; [onSelect] is told the
 * one tapped, chosen or not (GR-2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmojiPicker(selected: String?, onSelect: (String) -> Unit) {
    var typing by rememberSaveable { mutableStateOf(false) }
    val other = selected?.takeIf { it !in EMOJI }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (emoji in EMOJI) {
                EmojiTile(selected == emoji, onClick = { onSelect(emoji) }) {
                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                }
            }
            val description = stringResource(R.string.emoji_other)
            EmojiTile(
                other != null,
                onClick = { typing = !typing },
                Modifier.semantics { contentDescription = description },
            ) {
                if (other != null) Text(other, style = MaterialTheme.typography.headlineSmall)
                else Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            }
        }
        if (typing) {
            InlineEditor(
                other.orEmpty(),
                stringResource(R.string.emoji_other),
                onSave = { text ->
                    firstGrapheme(text)?.takeIf { it != selected }?.let(onSelect)
                    typing = false
                },
                onCancel = { typing = false },
                capitalization = KeyboardCapitalization.None,
            )
        }
    }
}

@Composable
private fun EmojiTile(
    chosen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(52.dp)
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
                .toggleable(value = chosen, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * The colours a group can be given: six to tap, and any other mixed from hue, saturation and
 * brightness (GR-2).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColourPicker(selected: Colour?, onSelect: (Colour) -> Unit) {
    var mixing by rememberSaveable { mutableStateOf(false) }
    val other = selected?.takeIf { it !in GROUP_COLOURS }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GROUP_COLOURS.forEachIndexed { index, colour ->
                Swatch(colour, selected == colour, stringResource(COLOUR_NAMES[index])) {
                    onSelect(colour)
                }
            }
            Swatch(other, other != null, stringResource(R.string.colour_other)) { mixing = !mixing }
        }
        if (mixing) Mixer(selected, onSelect)
    }
}

/** One colour to choose; with none, the way to mix another. */
@Composable
private fun Swatch(colour: Colour?, chosen: Boolean, name: String, onClick: () -> Unit) {
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
                .background(
                    if (colour == null) MaterialTheme.colorScheme.surfaceContainerHigh
                    else groupColour(colour)
                )
                .selectable(selected = chosen, onClick = onClick, role = Role.RadioButton)
                .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        when {
            colour == null -> Icon(painterResource(R.drawable.ic_add), null)
            chosen -> Icon(painterResource(R.drawable.ic_check), null, tint = onGroupColour(colour))
        }
    }
}

/** Hue, saturation and brightness, starting from [selected]; every move chooses the colour. */
@Composable
private fun Mixer(selected: Colour?, onSelect: (Colour) -> Unit) {
    var hsv by rememberSaveable {
        val start = FloatArray(3)
        android.graphics.Color.colorToHSV(groupColour(selected).toArgb(), start)
        mutableStateOf(start.toList())
    }
    fun move(index: Int, value: Float) {
        hsv = hsv.toMutableList().also { it[index] = value }
        val argb = Color.hsv(hsv[0], hsv[1], hsv[2]).toArgb()
        onSelect(Colour.parse("#%06X".format(argb and 0xFFFFFF)))
    }
    val labels = listOf(R.string.colour_hue, R.string.colour_saturation, R.string.colour_brightness)
    labels.forEachIndexed { index, label ->
        val name = stringResource(label)
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = hsv[index],
            onValueChange = { move(index, it) },
            valueRange = if (index == 0) 0f..359f else 0f..1f,
            modifier = Modifier.semantics { contentDescription = name },
        )
    }
}
