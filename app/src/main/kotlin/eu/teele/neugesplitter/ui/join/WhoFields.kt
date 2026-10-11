package eu.teele.neugesplitter.ui.join

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.ui.GroupAvatar

/** Who the user can be in [group]: every member not removed (BE-6). */
internal fun candidates(group: Group): List<Member> =
    group.members.values.filter { it.id !in group.removed }

/** A group to join or import: its look, its name and how much it holds. */
@Composable
internal fun GroupSummary(group: Group, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GroupAvatar(group.name, group.emoji, group.colour)
            Column(Modifier.padding(start = 16.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge)
                Text(sizeOf(group), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** How many members and entries [group] has. */
@Composable
internal fun sizeOf(group: Group): String {
    val members = candidates(group).size
    val entries = group.entries.size
    return pluralStringResource(R.plurals.join_members, members, members) +
        " · " +
        pluralStringResource(R.plurals.join_entries, entries, entries)
}

/**
 * Who the user is in a group: one of [members], or a new member named [name] (BE-6).
 *
 * @param chosen the member chosen; null while none is, or a new one is being named.
 * @param rejected the new member's name is past the limits every reader holds to.
 */
@Composable
internal fun WhoFields(
    members: List<Member>,
    chosen: MemberId?,
    adding: Boolean,
    name: String,
    rejected: Boolean,
    onChoose: (MemberId) -> Unit,
    onAdding: () -> Unit,
    onName: (String) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.join_who), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.join_who_text), color = colours.onSurfaceVariant)
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        members.forEach { member ->
            Choice(
                selected = chosen == member.id,
                onClick = { onChoose(member.id) },
                leading = { Initial(member) },
                name = member.name,
            )
            HorizontalDivider()
        }
        Choice(
            selected = adding,
            onClick = onAdding,
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
    if (adding) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text(stringResource(R.string.member_name)) },
            isError = rejected,
            supportingText =
                if (rejected) {
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
