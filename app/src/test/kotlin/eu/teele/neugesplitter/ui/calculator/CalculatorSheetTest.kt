package eu.teele.neugesplitter.ui.calculator

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class CalculatorSheetTest {

    @get:Rule val compose = createComposeRule()

    private var confirmed: Amount? = null

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun show(start: Amount? = null, scale: Scale = Scale.DEFAULT) {
        compose.setContent {
            NeugesplitterTheme {
                CalculatorSheet(start, scale, onConfirm = { confirmed = it }, onDismiss = {})
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun key(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasClickAction())

    private fun press(keys: String) {
        for (c in keys) {
            when (c) {
                '+' -> compose.onNodeWithContentDescription("Plus").performClick()
                '÷' -> compose.onNodeWithContentDescription("Divided by").performClick()
                '×' -> compose.onNodeWithContentDescription("Times").performClick()
                else -> key("$c").performClick()
            }
        }
    }

    private fun confirm() = compose.onNodeWithContentDescription("OK").performClick()

    @Test
    fun `a typed expression is worked out left to right and confirmed (AM-2, AM-3, AM-5)`() {
        show()
        press("12+3")
        confirm()
        assertEquals(cents(1500), confirmed)
    }

    @Test
    fun `a quotient is rounded to the scale (AM-7)`() {
        show()
        press("10÷3")
        confirm()
        assertEquals(cents(333), confirmed)
    }

    @Test
    fun `the start value is shown and confirms unchanged (AM-2)`() {
        show(start = cents(1250))
        compose.onNodeWithText("12.50").assertExists()
        confirm()
        assertEquals(cents(1250), confirmed)
    }

    @Test
    fun `a digit replaces the start value (AM-4)`() {
        show(start = cents(1250))
        press("7")
        confirm()
        assertEquals(cents(700), confirmed)
    }

    @Test
    fun `confirming is refused while the calculation is empty (AM-6)`() {
        show()
        compose.onNodeWithContentDescription("OK").assertIsNotEnabled()
        assertNull(confirmed)
    }

    @Test
    fun `an operator still waiting for its number is left out when confirming (AM-6)`() {
        show()
        press("5+")
        confirm()
        assertEquals(cents(500), confirmed)
    }

    @Test
    fun `dividing by zero is reported and cannot be confirmed (AM-6)`() {
        show()
        press("5÷0")
        compose.onNodeWithText("Cannot divide by zero").assertExists()
        compose.onNodeWithContentDescription("OK").assertIsNotEnabled()
    }
}
