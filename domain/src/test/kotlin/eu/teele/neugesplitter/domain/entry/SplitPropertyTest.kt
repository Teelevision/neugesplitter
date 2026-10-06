package eu.teele.neugesplitter.domain.entry

import eu.teele.neugesplitter.domain.arbSplit
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The truncation bound of format-spec 7, which TR-46 asks to be held as a property. */
class SplitPropertyTest {

    @Test
    fun `parts never exceed the amount and fall short by less than one unit per member (SP-7)`() {
        runBlocking {
            checkAll(arbSplit) { (amount, side) ->
                val parts = side.parts(amount)
                val shortfall = amount.units - parts.values.sumOf { it.units }
                assertTrue(
                    shortfall >= 0 && shortfall < parts.size,
                    "shortfall $shortfall outside [0, ${parts.size}) for $side of $amount",
                )
            }
        }
    }
}
