package eu.teele.neugesplitter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.teele.neugesplitter.domain.calculator.amountIn
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.ListedGroup
import eu.teele.neugesplitter.ui.GroupAvatar
import eu.teele.neugesplitter.ui.editor.EntryEditorScreen
import eu.teele.neugesplitter.ui.editor.EntryEditorViewModel
import java.text.DecimalFormatSymbols

/**
 * Adds an entry for an amount selected in another app, from its text selection toolbar (AM-9): to
 * the only group straight away, or to the one the user picks.
 */
class AddFromTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        // Which separator is the decimal one waits for the group; whether there is a number does
        // not.
        if (amountIn(text, Scale.DEFAULT) == null) {
            Toast.makeText(this, R.string.process_text_nothing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val graph = (application as Neugesplitter).graph
        themed(graph) { AddFromText(graph, text, onDone = ::finish) }
    }
}

@Composable
private fun AddFromText(graph: AppGraph, text: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val listed by
        remember { graph.groups.list(graph::today) }
            .collectAsStateWithLifecycle(initialValue = null)
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = listed ?: return
    LaunchedEffect(groups.isEmpty()) {
        if (groups.isEmpty()) {
            Toast.makeText(context, R.string.process_text_no_group, Toast.LENGTH_SHORT).show()
            onDone()
        }
    }
    val group = groups.find { it.id.hex == chosen } ?: groups.singleOrNull()
    if (group == null) {
        GroupPicker(groups, onPick = { chosen = it.id.hex }, onClose = onDone)
        return
    }
    val separator =
        DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0]).decimalSeparator
    val settlement = stringResource(R.string.settle_description)
    val transfer = stringResource(R.string.kind_transfer)
    EntryEditorScreen(
        viewModel(key = group.id.hex) {
            EntryEditorViewModel(
                graph.groups,
                group.id,
                entry = null,
                duplicate = false,
                settling = null,
                settlement,
                transfer,
                separator,
                graph::identifier,
                graph::today,
                graph::now,
                prefilled = amountIn(text, group.scale),
            )
        },
        onClose = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupPicker(
    groups: List<ListedGroup>,
    onPick: (ListedGroup) -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.process_text_pick)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel))
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(groups, key = { it.id.hex }) { group ->
                ListItem(
                    headlineContent = {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { GroupAvatar(group.name, group.emoji, group.colour) },
                    modifier = Modifier.clickable { onPick(group) },
                )
            }
        }
    }
}
