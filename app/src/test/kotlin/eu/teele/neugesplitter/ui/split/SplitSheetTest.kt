package eu.teele.neugesplitter.ui.split

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import eu.teele.neugesplitter.domain.entry.Scheme
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class SplitSheetTest {

    @get:Rule val compose = createComposeRule()

    private fun id(n: Int) = "%032x".format(n)

    private val anna = Member(MemberId.parse(id(1)), "Anna")
    private val ben = Member(MemberId.parse(id(2)), "Ben")
    private val clara = Member(MemberId.parse(id(3)), "Clara")

    private var done: Split? = null

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun show(start: Split, amount: Amount? = cents(3000)) {
        compose.setContent {
            NeugesplitterTheme {
                SplitSheet(
                    title = "For",
                    members = listOf(anna, ben, clara),
                    start = start,
                    amount = amount,
                    scale = Scale.DEFAULT,
                    locale = Locale.US,
                    onDone = { done = it },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Anna").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ok(): Split {
        compose.onNodeWithText("OK").performClick()
        return done!!
    }

    @Test
    fun `members are listed with the part each comes to (SP-2, SP-6)`() {
        show(Split())
        compose.onNodeWithText("Split “For”").assertExists()
        compose.onNodeWithText("Anna").assertExists()
        compose.onNodeWithText("Ben").assertExists()
        compose.onNodeWithText("Clara").assertExists()
        assertEquals(3, compose.onAllNodesWithText("10.00").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `the chosen scheme is handed over on OK (SP-1)`() {
        show(Split())
        for (label in listOf("Equal", "Shares", "Adjusted", "Exact")) {
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithText("Shares").performClick()
        compose.onNodeWithText("Exact").performClick()
        assertEquals(null, done)
        assertEquals(Scheme.ABSOLUTE, ok().scheme)
    }

    @Test
    fun `shares show their parts and total, and what is typed is handed over (SP-3, SP-6)`() {
        val shares = mapOf(anna.id to "2", ben.id to "1", clara.id to "1")
        show(Split(Scheme.SHARES, shares = shares), amount = cents(4000))
        compose.onNodeWithText("20.00").assertExists()
        assertEquals(2, compose.onAllNodesWithText("10.00").fetchSemanticsNodes().size)
        compose.onNodeWithText("4 shares in all").assertExists()
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("3")
        compose.onNodeWithText("6 shares in all").assertExists()
        assertEquals("3", ok().input(ben.id))
    }

    @Test
    fun `exact values leave the rest to those left blank (SP-5, SP-6)`() {
        show(Split(Scheme.ABSOLUTE, absolute = mapOf(anna.id to "20.00")))
        compose
            .onNode(hasText("20.00") and !hasContentDescription("Amount for Anna"))
            .assertExists()
        assertEquals(2, compose.onAllNodesWithText("5.00").fetchSemanticsNodes().size)
        compose.onNodeWithText("Adds up to the amount").assertExists()
        calculate("Clara", "7")
        compose.onNodeWithContentDescription("Amount for Clara").assertTextEquals("7.00")
        calculate("Anna", "C")
        compose.onNodeWithContentDescription("Amount for Anna").assertTextEquals("")
        val split = ok()
        assertEquals("7.00", split.input(clara.id))
        assertEquals("", split.input(anna.id))
    }

    @Test
    fun `an adjustment is worked out on the calculator and may be negative (SP-4, AM-2)`() {
        show(Split(Scheme.ADJUSTMENTS))
        calculate("Ben", "0-5")
        compose.onNodeWithContentDescription("Amount for Ben").assertTextEquals("-5.00")
        compose.onNodeWithText("Adjustments add up to −5.00").assertExists()
        assertEquals("-5.00", ok().input(ben.id))
    }

    /** Opens the calculator on [member]'s value, presses [keys] and confirms. */
    private fun calculate(member: String, keys: String) {
        compose.onNodeWithContentDescription("Amount for $member").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isNotEmpty()
        }
        for (key in keys) {
            when (key) {
                '-' -> compose.onNodeWithContentDescription("Minus").performClick()
                'C' -> compose.onNodeWithContentDescription("Clear").performClick()
                else -> compose.onNode(hasText("$key") and hasClickAction()).performClick()
            }
        }
        compose.onNodeWithContentDescription("OK").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("OK").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `a value that is not a number is called out (SP-6)`() {
        show(Split(Scheme.SHARES, shares = mapOf(ben.id to "x")))
        compose.onNodeWithText("Not every value is a number").assertExists()
    }

    @Test
    fun `plus and minus change a member's shares, and minus stops at none (SP-3)`() {
        show(Split(Scheme.SHARES, shares = mapOf(anna.id to "1", ben.id to "0")))
        val fields = compose.onAllNodes(hasSetTextAction())
        compose.onNodeWithContentDescription("Fewer shares for Ben").assertIsNotEnabled()
        compose.onNodeWithContentDescription("More shares for Ben").performClick()
        fields[1].assertTextEquals("1")
        compose.onNodeWithContentDescription("Fewer shares for Ben").assertIsEnabled()
        compose.onNodeWithContentDescription("More shares for Anna").performClick()
        fields[0].assertTextEquals("2")
        compose.onNodeWithContentDescription("Fewer shares for Anna").performClick()
        compose.onNodeWithContentDescription("Fewer shares for Anna").performClick()
        fields[0].assertTextEquals("0")
        compose.onNodeWithContentDescription("Fewer shares for Anna").assertIsNotEnabled()
    }

    @Test
    fun `focusing a shares field selects its text so typing replaces it (SP-3)`() {
        show(Split(Scheme.SHARES, shares = mapOf(anna.id to "12")))
        val field = compose.onAllNodes(hasSetTextAction())[0]
        field.requestFocus()
        compose.waitUntil(5_000) {
            field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange) ==
                TextRange(0, 2)
        }
        field.performTextReplacement("5")
        field.assertTextEquals("5")
    }

    @Test
    fun `only shares get a stepper (SP-3)`() {
        show(Split(Scheme.ABSOLUTE))
        assertEquals(
            0,
            compose
                .onAllNodes(hasContentDescription("More shares", substring = true))
                .fetchSemanticsNodes()
                .size,
        )
    }
}
