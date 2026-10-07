package eu.teele.neugesplitter.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.entry.Kind
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.ui.formatted
import eu.teele.neugesplitter.ui.kindIcon
import eu.teele.neugesplitter.ui.kindLabel
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.sideLabels
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/** What an entry is, who paid and who benefited, and each one's part (ui-design 5, HI-4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    viewModel: EntryDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(state) { if (state.loaded && detail == null) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (detail == null) return@TopAppBar
                    IconButton(onClick = onEdit) {
                        Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_options),
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.duplicate)) },
                                onClick = {
                                    menu = false
                                    onDuplicate()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history)) },
                                onClick = {
                                    menu = false
                                    onHistory()
                                },
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        if (detail != null) {
            Content(detail, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun Content(detail: Detail, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val entry = detail.entry
    val (payersLabel, beneficiariesLabel) = sideLabels(entry.kind)
    val amount = formatted(entry.amount, detail.scale, locale)
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(kindIcon(entry.kind)),
                    stringResource(kindLabel(entry.kind)),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(entry.description, style = MaterialTheme.typography.headlineSmall)
                Text(
                    longDate(entry.date, locale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            if (entry.kind == Kind.INCOME) "+$amount" else amount,
            style = MaterialTheme.typography.displaySmall.merge(TABULAR),
        )
        Parts(stringResource(payersLabel), detail.payers, detail.scale, locale)
        Parts(stringResource(beneficiariesLabel), detail.beneficiaries, detail.scale, locale)
        HorizontalDivider()
        Text(
            listOfNotNull(
                    detail.author?.let { stringResource(R.string.created_by, it) },
                    stringResource(R.string.entry_edited).takeIf { detail.edited },
                )
                .joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Parts(title: String, parts: List<Part>, scale: Scale, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        for (part in parts) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    part.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                )
                Text(
                    formatted(part.amount.roundedTo(scale), scale, locale),
                    style = MaterialTheme.typography.bodyLarge.merge(TABULAR),
                )
            }
        }
    }
}
