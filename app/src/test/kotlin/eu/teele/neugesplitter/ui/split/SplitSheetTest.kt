package eu.teele.neugesplitter.ui.split

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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

    private var scheme: Scheme? = null
    private var typed: Pair<MemberId, String>? = null

    private fun cents(value: Long) = Amount.ofUnits(value * 1_000_000L)

    private fun show(split: Split, amount: Amount? = cents(3000)) {
        compose.setContent {
            NeugesplitterTheme {
                SplitSheet(
                    title = "For",
                    members = listOf(anna, ben, clara),
                    split = split,
                    amount = amount,
                    scale = Scale.DEFAULT,
                    locale = Locale.US,
                    onScheme = { scheme = it },
                    onTyped = { member, text -> typed = member to text },
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Anna").fetchSemanticsNodes().isNotEmpty()
        }
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
    fun `choosing a scheme reports it (SP-1)`() {
        show(Split())
        for (label in listOf("Equal", "Shares", "Adjusted", "Exact")) {
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithText("Exact").performClick()
        assertEquals(Scheme.ABSOLUTE, scheme)
        compose.onNodeWithText("Shares").performClick()
        assertEquals(Scheme.SHARES, scheme)
    }

    @Test
    fun `shares show their parts and total, and typing reports the member (SP-3, SP-6)`() {
        val shares = mapOf(anna.id to "2", ben.id to "1", clara.id to "1")
        show(Split(Scheme.SHARES, shares = shares), amount = cents(4000))
        compose.onNodeWithText("20.00").assertExists()
        assertEquals(2, compose.onAllNodesWithText("10.00").fetchSemanticsNodes().size)
        compose.onNodeWithText("4 shares in all").assertExists()
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("3")
        assertEquals(ben.id to "3", typed)
    }

    @Test
    fun `exact values leave the rest to those left blank (SP-5, SP-6)`() {
        show(Split(Scheme.ABSOLUTE, absolute = mapOf(anna.id to "20.00")))
        compose.onNode(hasText("20.00") and !hasSetTextAction()).assertExists()
        assertEquals(2, compose.onAllNodesWithText("5.00").fetchSemanticsNodes().size)
        compose.onNodeWithText("Adds up to the amount").assertExists()
        compose.onAllNodes(hasSetTextAction())[2].performTextReplacement("7")
        assertEquals(clara.id to "7", typed)
    }

    @Test
    fun `a value that is not a number is called out (SP-6)`() {
        show(Split(Scheme.SHARES, shares = mapOf(ben.id to "x")))
        compose.onNodeWithText("Not every value is a number").assertExists()
    }
}
