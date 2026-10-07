package eu.teele.neugesplitter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.teele.neugesplitter.ui.App
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as Neugesplitter).graph
        setContent { NeugesplitterTheme { App(graph) } }
    }
}
