package eu.teele.neugesplitter

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.ui.App
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as Neugesplitter).graph
        graph.syncing.opened()
        setContent {
            val theme by graph.preferences.theme.collectAsStateWithLifecycle()
            val dark =
                when (theme) {
                    Theme.SYSTEM -> isSystemInDarkTheme()
                    Theme.LIGHT -> false
                    Theme.DARK -> true
                }
            // Bar icons follow the chosen theme, not the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle =
                        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            NeugesplitterTheme(dark = dark) { App(graph) }
        }
    }

    /**
     * Moves every group to the local date, which may have changed while the app was away, so that
     * what fell due meanwhile is shown on screens that stayed open (RE-14).
     */
    override fun onResume() {
        super.onResume()
        val graph = (application as Neugesplitter).graph
        graph.scope.launch { graph.groups.refresh(graph.today()) }
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
