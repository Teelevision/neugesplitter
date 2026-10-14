package eu.teele.neugesplitter

import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** The group a tapped notification told about, as hex, until the app has opened it. */
    private val notified = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as Neugesplitter).graph
        graph.syncing.opened()
        // A recreated activity has opened the group already.
        if (savedInstanceState == null) notified.value = intent.getStringExtra(GROUP)
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
            val group by notified.collectAsStateWithLifecycle()
            NeugesplitterTheme(dark = dark) {
                App(graph, notified = group, onNotifiedTaken = { notified.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(GROUP)?.let { notified.value = it }
    }

    /** On screen, Activity shows what is new, so notifications about it are taken back. */
    override fun onStart() {
        super.onStart()
        val graph = (application as Neugesplitter).graph
        graph.shown = true
        graph.notifications.clear()
    }

    override fun onStop() {
        super.onStop()
        (application as Neugesplitter).graph.shown = false
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

    companion object {
        /** The extra naming the group a notification told about, as hex. */
        const val GROUP = "group"

        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
