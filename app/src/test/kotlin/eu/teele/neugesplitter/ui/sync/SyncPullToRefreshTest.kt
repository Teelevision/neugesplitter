package eu.teele.neugesplitter.ui.sync

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class SyncPullToRefreshTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `pulling a shared group refreshes it`() {
        var refreshes = 0
        show(shared = true) { refreshes++ }

        pullDown()

        compose.waitUntil(5_000) { refreshes == 1 }
    }

    @Test
    fun `a fast sync keeps spinning for a moment, and another pull meanwhile waits (SY-9)`() {
        var refreshes = 0
        show(shared = true) { refreshes++ }
        compose.mainClock.autoAdvance = false

        pullDown()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, refreshes)
        pullDown()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, refreshes)

        compose.mainClock.advanceTimeBy(1_500)
        pullDown()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(2, refreshes)
    }

    @Test
    fun `pulling a local group does not refresh it`() {
        var refreshes = 0
        show(shared = false) { refreshes++ }

        pullDown()
        compose.waitForIdle()

        assertEquals(0, refreshes)
    }

    private fun show(shared: Boolean, onRefresh: suspend () -> Unit) {
        compose.setContent {
            SyncPullToRefresh(shared, onRefresh, Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) { items((0..30).toList()) { Text("Item $it") } }
            }
        }
    }

    private fun pullDown() {
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(width / 2f, height * 0.2f),
                end = Offset(width / 2f, height * 0.8f),
                durationMillis = 500,
            )
        }
    }
}
