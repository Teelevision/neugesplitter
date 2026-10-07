package eu.teele.neugesplitter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.teele.neugesplitter.AppGraph
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.balance.Transfer
import eu.teele.neugesplitter.domain.value.Amount
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.ui.detail.EntryDetailScreen
import eu.teele.neugesplitter.ui.detail.EntryDetailViewModel
import eu.teele.neugesplitter.ui.editor.EntryEditorScreen
import eu.teele.neugesplitter.ui.editor.EntryEditorViewModel
import eu.teele.neugesplitter.ui.grouplist.GroupListScreen
import eu.teele.neugesplitter.ui.grouplist.GroupListViewModel
import eu.teele.neugesplitter.ui.history.EntryHistoryScreen
import eu.teele.neugesplitter.ui.history.EntryHistoryViewModel
import eu.teele.neugesplitter.ui.newgroup.NewGroupScreen
import eu.teele.neugesplitter.ui.newgroup.NewGroupViewModel
import eu.teele.neugesplitter.ui.overview.OverviewScreen
import eu.teele.neugesplitter.ui.overview.OverviewViewModel
import eu.teele.neugesplitter.ui.people.PeopleScreen
import eu.teele.neugesplitter.ui.people.PeopleViewModel
import eu.teele.neugesplitter.ui.settings.GroupSettingsScreen
import eu.teele.neugesplitter.ui.settings.GroupSettingsViewModel
import java.text.DecimalFormatSymbols
import kotlinx.serialization.Serializable

@Serializable data object GroupList : NavKey

@Serializable data object NewGroup : NavKey

/** @property group the group's id, as hex. */
@Serializable data class GroupOverview(val group: String) : NavKey

/**
 * A new entry, or a change to [entry]; with [duplicate], a new entry copied from it. With [from],
 * [to] and [amount] (in 10⁻⁸ units), a new entry recording that transfer. Ids are hex.
 */
@Serializable
data class EntryEditor(
    val group: String,
    val entry: String? = null,
    val duplicate: Boolean = false,
    val from: String? = null,
    val to: String? = null,
    val amount: Long? = null,
) : NavKey {
    val settling: Transfer?
        get() =
            if (from == null || to == null || amount == null) null
            else Transfer(MemberId.parse(from), MemberId.parse(to), Amount.ofUnits(amount))
}

@Serializable data class EntryDetail(val group: String, val entry: String) : NavKey

@Serializable data class EntryHistory(val group: String, val entry: String) : NavKey

/** Every screen, and how one leads to the next (ui-design 2). */
@Composable
fun App(graph: AppGraph) {
    val stack = rememberNavBackStack(GroupList)
    val pop = { stack.removeLastOrNull() }
    // An entry deleted from its detail screen, for the overview under it to offer undo for.
    var deleting by remember { mutableStateOf<String?>(null) }
    NavDisplay(
        backStack = stack,
        onBack = { pop() },
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<GroupList> {
                    GroupListScreen(
                        viewModel { GroupListViewModel(graph.groups, graph::today) },
                        onNewGroup = { stack.add(NewGroup) },
                        onOpen = { stack.add(GroupOverview(it.hex)) },
                    )
                }
                entry<NewGroup> {
                    NewGroupScreen(
                        viewModel {
                            NewGroupViewModel(
                                graph.groups,
                                graph::identifier,
                                graph::today,
                                graph::now,
                            )
                        },
                        onClose = { pop() },
                        onCreated = {
                            pop()
                            stack.add(GroupOverview(it.hex))
                        },
                    )
                }
                entry<GroupOverview> { key ->
                    val group = GroupId.parse(key.group)
                    GroupScreen(onNew = { stack.add(EntryEditor(key.group)) }) {
                        destination,
                        modifier ->
                        when (destination) {
                            Destination.OVERVIEW ->
                                OverviewScreen(
                                    viewModel {
                                        OverviewViewModel(
                                            graph.groups,
                                            group,
                                            graph::identifier,
                                            graph::today,
                                            graph::now,
                                            graph.scope,
                                        )
                                    },
                                    deleteRequested = deleting?.let(EntryId::parse),
                                    onDeleteTaken = { deleting = null },
                                    onBack = { pop() },
                                    onOpen = { stack.add(EntryDetail(key.group, it.hex)) },
                                    onEdit = { stack.add(EntryEditor(key.group, it.hex)) },
                                    onDuplicate = {
                                        stack.add(EntryEditor(key.group, it.hex, duplicate = true))
                                    },
                                    onHistory = { stack.add(EntryHistory(key.group, it.hex)) },
                                    modifier = modifier,
                                )
                            Destination.PEOPLE ->
                                PeopleScreen(
                                    viewModel {
                                        PeopleViewModel(
                                            graph.groups,
                                            group,
                                            graph::identifier,
                                            graph::today,
                                            graph::now,
                                        )
                                    },
                                    onBack = { pop() },
                                    onRecord = {
                                        stack.add(
                                            EntryEditor(
                                                key.group,
                                                from = it.from.hex,
                                                to = it.to.hex,
                                                amount = it.amount.units,
                                            )
                                        )
                                    },
                                    onOpen = { stack.add(EntryDetail(key.group, it.hex)) },
                                    modifier = modifier,
                                )
                            Destination.SETTINGS ->
                                GroupSettingsScreen(
                                    viewModel {
                                        GroupSettingsViewModel(
                                            graph.groups,
                                            group,
                                            graph::identifier,
                                            graph::today,
                                            graph::now,
                                        )
                                    },
                                    onBack = { pop() },
                                    onDeleted = { while (stack.size > 1) pop() },
                                    modifier = modifier,
                                )
                        }
                    }
                }
                entry<EntryEditor> { key ->
                    val separator =
                        DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0])
                            .decimalSeparator
                    val settlement = stringResource(R.string.settle_description)
                    EntryEditorScreen(
                        viewModel {
                            EntryEditorViewModel(
                                graph.groups,
                                GroupId.parse(key.group),
                                key.entry?.let(EntryId::parse),
                                key.duplicate,
                                key.settling,
                                settlement,
                                separator,
                                graph::identifier,
                                graph::today,
                                graph::now,
                            )
                        },
                        onClose = { pop() },
                    )
                }
                entry<EntryDetail> { key ->
                    EntryDetailScreen(
                        viewModel {
                            EntryDetailViewModel(
                                graph.groups,
                                GroupId.parse(key.group),
                                EntryId.parse(key.entry),
                            )
                        },
                        onBack = { pop() },
                        onEdit = { stack.add(EntryEditor(key.group, key.entry)) },
                        onDuplicate = {
                            stack.add(EntryEditor(key.group, key.entry, duplicate = true))
                        },
                        onHistory = { stack.add(EntryHistory(key.group, key.entry)) },
                        onDelete = {
                            pop()
                            deleting = key.entry
                        },
                    )
                }
                entry<EntryHistory> { key ->
                    EntryHistoryScreen(
                        viewModel {
                            EntryHistoryViewModel(
                                graph.groups,
                                GroupId.parse(key.group),
                                EntryId.parse(key.entry),
                            )
                        },
                        onBack = { pop() },
                    )
                }
            },
    )
}
