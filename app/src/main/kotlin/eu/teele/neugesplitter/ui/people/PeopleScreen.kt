package eu.teele.neugesplitter.ui.people

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.balance.Transfer
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.ui.EntryRow
import eu.teele.neugesplitter.ui.formatted
import java.util.Locale

private val TABULAR = TextStyle(fontFeatureSettings = "tnum")

/**
 * Every member's balance and the transfers that settle them (ui-design 4, BA-1 to BA-6), and how
 * members are added, renamed and removed (GR-5, GR-6, ID-2). A tap opens the member's sheet, a long
 * press renames in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    onBack: () -> Unit,
    onRecord: (Transfer) -> Unit,
    onOpen: (EntryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    var sheet by remember { mutableStateOf<MemberId?>(null) }
    var renaming by remember { mutableStateOf<MemberId?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    // Deleted while open, which only another screen can do.
    LaunchedEffect(state) { if (state.loaded && state.title == null) onBack() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(state.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.title == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "balances") { Heading(R.string.balances_header) }
            items(state.people, key = { it.id.hex }) { person ->
                if (renaming == person.id) {
                    NameEditor(
                        person.name,
                        onSave = {
                            viewModel.rename(person.id, it)
                            renaming = null
                        },
                        onCancel = { renaming = null },
                    )
                } else {
                    PersonRow(
                        person,
                        state.scale,
                        locale,
                        Modifier.combinedClickable(
                            onClick = { sheet = person.id },
                            onLongClick = {
                                adding = false
                                renaming = person.id
                            },
                        ),
                    )
                }
            }
            item(key = "add") {
                if (adding) {
                    NameEditor(
                        "",
                        onSave = {
                            viewModel.add(it)
                            adding = false
                        },
                        onCancel = { adding = false },
                    )
                } else {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    renaming = null
                                    adding = true
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                        Text(
                            stringResource(R.string.member_add),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            item(key = "settling") { Heading(R.string.settling_header) }
            if (state.transfers.isEmpty()) {
                item(key = "settled") {
                    Text(
                        stringResource(R.string.group_settled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            items(state.transfers, key = { "${it.from.hex}-${it.to.hex}" }) { transfer ->
                TransferRow(
                    transfer,
                    state.name(transfer.from),
                    state.name(transfer.to),
                    state.scale,
                    locale,
                    onRecord = { onRecord(transfer) },
                )
            }
        }
    }

    val member = state.people.firstOrNull { it.id == sheet }
    if (member != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text(
                    member.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SheetAction(R.drawable.ic_edit, R.string.member_rename) {
                    sheet = null
                    adding = false
                    renaming = member.id
                }
                // Whoever is acting cannot remove themselves, and nobody is removed twice (GR-6).
                if (!member.me && !member.removed) {
                    SheetAction(R.drawable.ic_remove, R.string.member_remove) { removing = true }
                }
                Heading(R.string.member_entries)
                if (member.entries.isEmpty()) {
                    Text(
                        stringResource(R.string.member_no_entries),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                for (entry in member.entries) {
                    EntryRow(
                        entry,
                        state.scale,
                        locale,
                        Modifier.clickable {
                            sheet = null
                            onOpen(entry.id)
                        },
                    )
                }
            }
        }
        if (removing) {
            AlertDialog(
                onDismissRequest = { removing = false },
                title = { Text(stringResource(R.string.member_remove_title, member.name)) },
                text = { Text(stringResource(R.string.member_remove_text, member.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.remove(member.id)
                            removing = false
                            sheet = null
                        }
                    ) {
                        Text(stringResource(R.string.member_remove_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { removing = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun SheetAction(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** A name being typed in place of its row; Done or the check saves, back or Escape cancels. */
@Composable
private fun NameEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val valid = name.isNotBlank()
    val save = { if (valid) onSave(name) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onCancel)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.member_name)) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier =
                Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                    val escape = it.key == Key.Escape && it.type == KeyEventType.KeyUp
                    if (escape) onCancel()
                    escape
                },
        )
        IconButton(onClick = { save() }, enabled = valid) {
            Icon(painterResource(R.drawable.ic_check), stringResource(R.string.save))
        }
        IconButton(onClick = onCancel) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
        }
    }
}

@Composable
private fun Heading(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics {
                heading()
            },
    )
}

/**
 * A member and their balance. Being owed and owing differ in words as well as in colour (UX-6), and
 * a removed member is greyed (GR-9).
 */
@Composable
private fun PersonRow(person: Person, scale: Scale, locale: Locale, modifier: Modifier) {
    val colours = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (person.removed) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (person.me) stringResource(R.string.member_you, person.name) else person.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (person.removed) {
            Text(
                stringResource(R.string.member_removed),
                style = MaterialTheme.typography.bodyMedium,
                color = colours.onSurfaceVariant,
            )
        }
        val amount = formatted(person.balance, scale, locale)
        val (label, container, content) =
            when {
                person.balance.isPositive ->
                    Triple(
                        stringResource(R.string.balance_owed, amount),
                        colours.tertiaryContainer,
                        colours.onTertiaryContainer,
                    )
                person.balance.isNegative ->
                    Triple(
                        stringResource(R.string.balance_owes, amount),
                        colours.secondaryContainer,
                        colours.onSecondaryContainer,
                    )
                else ->
                    Triple(
                        stringResource(R.string.balance_settled),
                        colours.surfaceContainerHigh,
                        colours.onSurfaceVariant,
                    )
            }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.merge(TABULAR),
            color = content,
            maxLines = 1,
            modifier =
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(container)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** "Anna pays Ben 12.30", with a way to record it as a transfer (BA-3, BA-6). */
@Composable
private fun TransferRow(
    transfer: Transfer,
    from: String,
    to: String,
    scale: Scale,
    locale: Locale,
    onRecord: () -> Unit,
) {
    val amount = formatted(transfer.amount, scale, locale)
    val sentence = stringResource(R.string.settle_transfer, from, to, amount)
    val start = sentence.lastIndexOf(amount)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append(sentence)
                if (start >= 0) {
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                        start,
                        start + amount.length,
                    )
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRecord) { Text(stringResource(R.string.settle_record)) }
    }
}
