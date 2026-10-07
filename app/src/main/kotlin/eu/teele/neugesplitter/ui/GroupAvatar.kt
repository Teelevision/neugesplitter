package eu.teele.neugesplitter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.domain.value.Colour
import eu.teele.neugesplitter.ui.theme.groupColour

/**
 * A group's emoji on its own colour, or the first letter of its name when it has no emoji (GR-2).
 * It repeats what the name beside it says, so screen readers skip it.
 */
@Composable
fun GroupAvatar(name: String, emoji: String?, colour: Colour?, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(groupColour(colour))
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji ?: name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
    }
}
