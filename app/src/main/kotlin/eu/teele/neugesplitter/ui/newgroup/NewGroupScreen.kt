package eu.teele.neugesplitter.ui.newgroup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.Scale
import eu.teele.neugesplitter.storage.ListedGroup
import eu.teele.neugesplitter.ui.ColourPicker
import eu.teele.neugesplitter.ui.EmojiPicker
import eu.teele.neugesplitter.ui.grouplist.GroupCard

private val NAME_OPTIONS =
    KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)

/**
 * A short pushed flow: the group, then who is in it, then where it is kept (ui-design 3, GR-2,
 * ID-2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(
    viewModel: NewGroupViewModel,
    onClose: () -> Unit,
    onCreated: (GroupId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back = { if (!viewModel.back()) onClose() }

    LaunchedEffect(state.created) { if (state.created) onCreated(state.id) }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (state.step) {
                                Step.DETAILS -> R.string.new_group
                                Step.MEMBERS -> R.string.members_title
                                Step.STORAGE -> R.string.storage_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = back) { Text(stringResource(R.string.back)) }
                    Button(onClick = viewModel::next, enabled = state.canContinue) {
                        Text(
                            stringResource(
                                if (state.step == Step.STORAGE) R.string.create_group
                                else R.string.next
                            )
                        )
                    }
                }
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
                Step.DETAILS -> Details(state, viewModel)
                Step.MEMBERS -> Members(state, viewModel)
                Step.STORAGE -> Storage(state)
            }
        }
    }
}

@Composable
private fun Details(state: NewGroupState, viewModel: NewGroupViewModel) {
    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::name,
        label = { Text(stringResource(R.string.group_name)) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.description,
        onValueChange = viewModel::description,
        label = { Text(stringResource(R.string.group_description)) },
        placeholder = { Text(stringResource(R.string.group_description_hint)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )

    Heading(stringResource(R.string.group_emoji))
    EmojiPicker(state.emoji, viewModel::emoji)

    Heading(stringResource(R.string.group_colour))
    ColourPicker(state.colour, viewModel::colour)

    Heading(stringResource(R.string.group_preview))
    GroupCard(ListedGroup(state.id, state.name, state.emoji, state.colour, Scale.DEFAULT, 0, null))
}

@Composable
private fun Members(state: NewGroupState, viewModel: NewGroupViewModel) {
    val focus = remember { FocusRequester() }
    var added by remember { mutableStateOf(false) }
    LaunchedEffect(state.others.size) {
        if (added) focus.requestFocus()
        added = false
    }

    Column {
        Heading(stringResource(R.string.members_header).uppercase())
        Text(
            stringResource(R.string.members_later),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedTextField(
        value = state.you,
        onValueChange = viewModel::you,
        label = { Text(stringResource(R.string.your_name)) },
        supportingText = { Text(stringResource(R.string.member_not_removable)) },
        trailingIcon = { Icon(painterResource(R.drawable.ic_lock), null) },
        singleLine = true,
        keyboardOptions = NAME_OPTIONS,
        modifier = Modifier.fillMaxWidth(),
    )
    state.others.forEachIndexed { index, name ->
        val last = index == state.others.lastIndex
        OutlinedTextField(
            value = name,
            onValueChange = { viewModel.other(index, it) },
            label = { Text(stringResource(R.string.member_name)) },
            trailingIcon = {
                IconButton(onClick = { viewModel.removeMember(index) }) {
                    Icon(
                        painterResource(R.drawable.ic_remove),
                        stringResource(R.string.remove_member, name),
                    )
                }
            },
            singleLine = true,
            keyboardOptions = NAME_OPTIONS,
            modifier =
                Modifier.fillMaxWidth().then(if (last) Modifier.focusRequester(focus) else Modifier),
        )
    }
    TextButton(
        onClick = {
            added = true
            viewModel.addMember()
        }
    ) {
        Icon(painterResource(R.drawable.ic_add), null)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.add_member))
    }
}

/** Only this device so far; shared storage arrives with the first backend (BE-1, DA-1). */
@Composable
private fun Storage(state: NewGroupState) {
    Column {
        Heading(stringResource(R.string.storage_header).uppercase())
        Text(
            stringResource(R.string.storage_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(Modifier.selectableGroup()) {
        OutlinedCard(
            modifier =
                Modifier.fillMaxWidth()
                    .selectable(selected = true, onClick = {}, role = Role.RadioButton),
            colors =
                CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.storage_local),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    RadioButton(selected = true, onClick = null)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.storage_local_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (state.failed) {
        Text(
            stringResource(R.string.create_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
