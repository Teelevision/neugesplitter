package eu.teele.neugesplitter.ui.join

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.ui.GroupAvatar
import eu.teele.neugesplitter.ui.backend.Checks
import eu.teele.neugesplitter.ui.backend.Failure
import eu.teele.neugesplitter.ui.backend.Found as FoundCard
import eu.teele.neugesplitter.ui.backend.Location
import eu.teele.neugesplitter.ui.backend.LocationFields
import eu.teele.neugesplitter.ui.backend.Secret

/**
 * Joins a group shared elsewhere (ui-design 3): its location, what is there, its passphrase if it
 * is encrypted, and who the user is in it (BE-5, BE-6, BE-9, BE-11).
 *
 * @param onDone called with the group joined, or held already and asked for.
 * @param onCreateHere called with an empty location to create a new group at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(
    viewModel: JoinViewModel,
    onClose: () -> Unit,
    onDone: (GroupId) -> Unit,
    onCreateHere: (Location) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = { if (!viewModel.back()) onClose() }

    LaunchedEffect(state.done) { state.done?.let(onDone) }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_group)) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                HorizontalDivider()
                Buttons(state, viewModel, back, onCreateHere)
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                JoinStep.LOCATION ->
                    LocationFields(
                        state.location,
                        state.invalid,
                        viewModel::location,
                        viewModel::paste,
                    )
                JoinStep.TEST -> {
                    Checks(state.verdict)
                    if (state.location.plainHttp) {
                        FoundCard(
                            R.drawable.ic_warning,
                            R.string.setup_http_title,
                            listOf(R.string.setup_http_text),
                            MaterialTheme.colorScheme.errorContainer,
                        )
                    }
                }
                JoinStep.CONTENTS -> Contents(state)
                JoinStep.PASSPHRASE -> Passphrase(state, viewModel)
                JoinStep.FETCH -> {
                    val failure = state.failure
                    if (failure == null) Busy(R.string.join_fetching) else Failure(failure)
                }
                JoinStep.WHO -> Who(state, viewModel)
            }
        }
    }
}

@Composable
private fun Buttons(
    state: JoinState,
    viewModel: JoinViewModel,
    back: () -> Unit,
    onCreateHere: (Location) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
        Spacer(Modifier.weight(1f))
        val (label, action, enabled) =
            when {
                state.step == JoinStep.CONTENTS && state.found == Found.EMPTY && !state.readOnly ->
                    Triple(R.string.join_create_here, { onCreateHere(state.location) }, true)
                state.step == JoinStep.CONTENTS && state.found == Found.HELD ->
                    Triple(R.string.join_open, viewModel::open, true)
                else ->
                    Triple(
                        when (state.step) {
                            JoinStep.LOCATION -> R.string.setup_test
                            JoinStep.TEST,
                            JoinStep.PASSPHRASE -> R.string.next
                            JoinStep.FETCH -> R.string.try_again
                            JoinStep.CONTENTS,
                            JoinStep.WHO -> R.string.join
                        },
                        viewModel::next,
                        state.canContinue,
                    )
            }
        Button(onClick = action, enabled = enabled) { Text(stringResource(label)) }
    }
}

@Composable
private fun Busy(text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(stringResource(text))
    }
}

/** Why the location offers nothing to join (BE-9). */
@Composable
private fun ColumnScope.Contents(state: JoinState) {
    val colours = MaterialTheme.colorScheme
    val (icon, title, text) =
        when (state.found) {
            Found.EMPTY ->
                Triple(
                    R.drawable.ic_folder,
                    R.string.join_empty,
                    if (state.readOnly) R.string.join_empty_read_only else R.string.join_empty_text,
                )
            Found.FOREIGN ->
                Triple(R.drawable.ic_warning, R.string.join_foreign, R.string.join_foreign_text)
            Found.HELD -> Triple(R.drawable.ic_check, R.string.join_held, R.string.join_held_text)
            Found.UNREADABLE ->
                Triple(
                    R.drawable.ic_warning,
                    R.string.join_unreadable,
                    R.string.join_unreadable_text,
                )
            null -> return
        }
    val error = state.found == Found.FOREIGN || state.found == Found.UNREADABLE
    Spacer(Modifier.size(48.dp))
    Box(
        modifier =
            Modifier.size(64.dp)
                .align(Alignment.CenterHorizontally)
                .background(
                    if (error) Color.Transparent else colours.secondaryContainer,
                    CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            null,
            tint = if (error) colours.error else colours.onSecondaryContainer,
            modifier = Modifier.size(if (error) 56.dp else 32.dp),
        )
    }
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        color = if (error) colours.error else colours.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(text),
        color = colours.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Passphrase(state: JoinState, viewModel: JoinViewModel) {
    var shown by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.join_passphrase_text))
    Secret(state.passphrase, viewModel::passphrase, R.string.passphrase, shown, { shown = it })
    if (state.wrong) {
        Text(
            stringResource(R.string.join_wrong_passphrase),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (state.busy) Busy(R.string.join_unlocking)
    state.failure?.let { Failure(it) }
}

/** The group fetched, and who the user is in it (BE-6). */
@Composable
private fun Who(state: JoinState, viewModel: JoinViewModel) {
    val group = state.group ?: return
    val colours = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_check), null, tint = colours.primary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.join_found), style = MaterialTheme.typography.titleMedium)
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GroupAvatar(group.name, group.emoji, group.colour)
            Column(Modifier.padding(start = 16.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge)
                val members = state.members.size
                val entries = group.entries.size
                Text(
                    pluralStringResource(R.plurals.join_members, members, members) +
                        " · " +
                        pluralStringResource(R.plurals.join_entries, entries, entries),
                    color = colours.onSurfaceVariant,
                )
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.join_who), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.join_who_text), color = colours.onSurfaceVariant)
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        state.members.forEach { member ->
            Choice(
                selected = state.chosen == member.id,
                onClick = { viewModel.choose(member.id) },
                leading = { Initial(member) },
                name = member.name,
            )
            HorizontalDivider()
        }
        Choice(
            selected = state.adding,
            onClick = viewModel::adding,
            leading = {
                Icon(
                    painterResource(R.drawable.ic_add),
                    null,
                    modifier = Modifier.size(40.dp).padding(8.dp),
                )
            },
            name = stringResource(R.string.join_new_member),
        )
    }
    if (state.adding) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::name,
            label = { Text(stringResource(R.string.member_name)) },
            isError = state.rejected,
            supportingText =
                if (state.rejected) {
                    { Text(stringResource(R.string.join_name_rejected)) }
                } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Choice(
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    name: String,
) {
    ListItem(
        headlineContent = { Text(name) },
        leadingContent = leading,
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected, onClick = onClick, role = Role.RadioButton),
    )
}

/** A member's first letter, which repeats the name beside it. */
@Composable
private fun Initial(member: Member) {
    Box(
        modifier =
            Modifier.size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            member.name.trim().take(1).uppercase(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
