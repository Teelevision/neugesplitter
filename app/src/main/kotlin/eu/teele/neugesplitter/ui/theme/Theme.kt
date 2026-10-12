package eu.teele.neugesplitter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import eu.teele.neugesplitter.domain.value.Colour

/** The brand palette of ui-design 8 and docs/ui/tokens; never the wallpaper's. */
internal val Light =
    lightColorScheme(
        primary = Color(0xFF496F2E),
        onPrimary = Color(0xFFFBFDFE),
        primaryContainer = Color(0xFFC7D2BE),
        onPrimaryContainer = Color(0xFF1F2F13),
        inversePrimary = Color(0xFFABBD9F),
        secondary = Color(0xFF4B716F),
        onSecondary = Color(0xFFFBFDFE),
        secondaryContainer = Color(0xFFC7D3D2),
        onSecondaryContainer = Color(0xFF202F2F),
        tertiary = Color(0xFF84AC29),
        onTertiary = Color(0xFF141613),
        tertiaryContainer = Color(0xFFD9E5BD),
        onTertiaryContainer = Color(0xFF374811),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFBFDFE),
        onBackground = Color(0xFF141613),
        surface = Color(0xFFFBFDFE),
        onSurface = Color(0xFF141613),
        surfaceVariant = Color(0xFFDBE3E7),
        onSurfaceVariant = Color(0xFF4A5155),
        surfaceDim = Color(0xFFDBE8F0),
        surfaceBright = Color(0xFFFBFDFE),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F8FA),
        surfaceContainer = Color(0xFFEEF4F8),
        surfaceContainerHigh = Color(0xFFE7F0F5),
        surfaceContainerHighest = Color(0xFFE2EDF3),
        surfaceTint = Color(0xFF496F2E),
        outline = Color(0xFF7E8A8F),
        outlineVariant = Color(0xFFCBD6DB),
        inverseSurface = Color(0xFF141613),
        inverseOnSurface = Color(0xFFE8E8E7),
        scrim = Color(0xFF000000),
    )

internal val Dark =
    darkColorScheme(
        primary = Color(0xFFABBD9F),
        onPrimary = Color(0xFF141613),
        primaryContainer = Color(0xFF344F21),
        onPrimaryContainer = Color(0xFFC7D2BE),
        inversePrimary = Color(0xFF496F2E),
        secondary = Color(0xFFACBEBD),
        onSecondary = Color(0xFF141613),
        secondaryContainer = Color(0xFF35504F),
        onSecondaryContainer = Color(0xFFC7D3D2),
        tertiary = Color(0xFFADC770),
        onTertiary = Color(0xFF141613),
        tertiaryContainer = Color(0xFF495F17),
        onTertiaryContainer = Color(0xFFD9E5BD),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF141613),
        onBackground = Color(0xFFE8E8E7),
        surface = Color(0xFF141613),
        onSurface = Color(0xFFE8E8E7),
        surfaceVariant = Color(0xFF434542),
        onSurfaceVariant = Color(0xFFC1CED5),
        surfaceDim = Color(0xFF0E100D),
        surfaceBright = Color(0xFF2B2D29),
        surfaceContainerLowest = Color(0xFF0B0C0A),
        surfaceContainerLow = Color(0xFF1B1D1A),
        surfaceContainer = Color(0xFF1E201D),
        surfaceContainerHigh = Color(0xFF242623),
        surfaceContainerHighest = Color(0xFF2B2D29),
        surfaceTint = Color(0xFFABBD9F),
        outline = Color(0xFFA1B1B8),
        outlineVariant = Color(0xFF4A5155),
        inverseSurface = Color(0xFFE8E8E7),
        inverseOnSurface = Color(0xFF141613),
        scrim = Color(0xFF000000),
    )

/**
 * The six colours offered first, in that order: Grün, Petrol, Blau, Violett, Beere, Ocker. A group
 * may take any other. They carry identity and nothing else (ui-design 8).
 */
val GROUP_COLOURS: List<Colour> =
    listOf("#4C7A3F", "#2A6E6B", "#3A6EA5", "#6A55A0", "#9C4270", "#9A5F1E").map(Colour::parse)

/** [colour] for drawing. */
fun groupColour(colour: Colour?): Color =
    Color(0xFF000000 or (colour ?: GROUP_COLOURS.first()).text.drop(1).toLong(16))

/**
 * Text and icons on [colour]: white or black, whichever stands out more, which is at least 4.58 to
 * 1 by WCAG for any colour a group may take (ui-design 8).
 */
fun onGroupColour(colour: Colour?): Color {
    val background = groupColour(colour).luminance()
    return if (1.05f / (background + 0.05f) >= (background + 0.05f) / 0.05f) Color.White
    else Color.Black
}

@Composable
fun NeugesplitterTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/**
 * Whether the user's text size leaves no room for a name and an amount side by side, so rows stack
 * them instead of cutting the name to a letter or two (UX-6, ui-design 9).
 */
@Composable @ReadOnlyComposable fun largeText(): Boolean = LocalDensity.current.fontScale > 1.2f
