package eu.teele.neugesplitter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals

/** Text against what it is drawn on, by the WCAG 2 ratio (UX-6, ui-design 8). */
class ContrastTest {

    private fun ratio(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    /** Every pair in [pairs] whose ratio falls below [least], by name. */
    private fun below(least: Double, pairs: Map<String, Pair<Color, Color>>): List<String> =
        pairs
            .filterValues { (a, b) -> ratio(a, b) < least }
            .map { (name, colours) -> "$name %.2f".format(ratio(colours.first, colours.second)) }

    private fun text(scheme: ColorScheme): Map<String, Pair<Color, Color>> =
        with(scheme) {
            val surfaces =
                mapOf(
                    "surface" to surface,
                    "surfaceDim" to surfaceDim,
                    "surfaceBright" to surfaceBright,
                    "surfaceContainerLowest" to surfaceContainerLowest,
                    "surfaceContainerLow" to surfaceContainerLow,
                    "surfaceContainer" to surfaceContainer,
                    "surfaceContainerHigh" to surfaceContainerHigh,
                    "surfaceContainerHighest" to surfaceContainerHighest,
                )
            val onSurfaces =
                surfaces.flatMap { (name, colour) ->
                    listOf(
                        "onSurface on $name" to (onSurface to colour),
                        "onSurfaceVariant on $name" to (onSurfaceVariant to colour),
                        // Text buttons and the selected destination's label.
                        "primary on $name" to (primary to colour),
                        "error on $name" to (error to colour),
                    )
                }
            mapOf(
                "onPrimary" to (onPrimary to primary),
                "onPrimaryContainer" to (onPrimaryContainer to primaryContainer),
                // A selected group's row.
                "onSurface on primaryContainer" to (onSurface to primaryContainer),
                "onSurfaceVariant on primaryContainer" to (onSurfaceVariant to primaryContainer),
                "onSecondary" to (onSecondary to secondary),
                "onSecondaryContainer" to (onSecondaryContainer to secondaryContainer),
                "onTertiary" to (onTertiary to tertiary),
                "onTertiaryContainer" to (onTertiaryContainer to tertiaryContainer),
                "onError" to (onError to error),
                "onErrorContainer" to (onErrorContainer to errorContainer),
                "onBackground" to (onBackground to background),
                "inverseOnSurface" to (inverseOnSurface to inverseSurface),
            ) + onSurfaces
        }

    @Test
    fun `text in the light theme reaches 4_5 to 1 against what it is drawn on (UX-6)`() {
        assertEquals(emptyList(), below(4.5, text(Light)))
    }

    @Test
    fun `text in the dark theme reaches 4_5 to 1 against what it is drawn on (UX-6)`() {
        assertEquals(emptyList(), below(4.5, text(Dark)))
    }

    @Test
    fun `outlines and the selected destination's icon reach 3 to 1 in both themes (UX-6)`() {
        for (scheme in listOf(Light, Dark)) {
            val shapes =
                mapOf(
                    "outline" to (scheme.outline to scheme.surface),
                    "primary on primaryContainer" to (scheme.primary to scheme.primaryContainer),
                )
            assertEquals(emptyList(), below(3.0, shapes))
        }
    }

    @Test
    fun `the six group colours carry white text at 5 to 1 (GR-2, ui-design 8)`() {
        val pairs = GROUP_COLOURS.associate { it.text to (Color.White to groupColour(it)) }
        assertEquals(emptyList(), below(5.0, pairs))
    }
}
