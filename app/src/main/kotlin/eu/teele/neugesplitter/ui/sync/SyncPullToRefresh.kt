package eu.teele.neugesplitter.ui.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SHORTEST_SPIN = 1500.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPullToRefresh(
    shared: Boolean,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!shared) {
        Box(modifier = modifier, content = content)
        return
    }

    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    try {
                        coroutineScope {
                            // A fast sync would only flash the spinner, which reads as nothing
                            // done.
                            val shown = launch { delay(SHORTEST_SPIN) }
                            onRefresh()
                            shown.join()
                        }
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
        modifier = modifier,
    ) {
        content()
    }
}
