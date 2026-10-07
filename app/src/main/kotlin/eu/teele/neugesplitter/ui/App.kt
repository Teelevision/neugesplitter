package eu.teele.neugesplitter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.teele.neugesplitter.AppGraph
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.ui.detail.EntryDetailScreen
import eu.teele.neugesplitter.ui.detail.EntryDetailViewModel
import eu.teele.neugesplitter.ui.editor.EntryEditorScreen
import eu.teele.neugesplitter.ui.editor.EntryEditorViewModel
import eu.teele.neugesplitter.ui.grouplist.GroupListScreen
import eu.teele.neugesplitter.ui.grouplist.GroupListViewModel
import eu.teele.neugesplitter.ui.newgroup.NewGroupScreen
import eu.teele.neugesplitter.ui.newgroup.NewGroupViewModel
import eu.teele.neugesplitter.ui.overview.OverviewScreen
import eu.teele.neugesplitter.ui.overview.OverviewViewModel
import kotlinx.serialization.Serializable

@Serializable data object GroupList : NavKey

@Serializable data object NewGroup : NavKey

/** @property group the group's id, as hex. */
@Serializable data class GroupOverview(val group: String) : NavKey

/**
 * A new entry, or a change to [entry]; with [duplicate], a new entry copied from it. Ids are hex.
 */
@Serializable
data class EntryEditor(
    val group: String,
    val entry: String? = null,
    val duplicate: Boolean = false,
) : NavKey

@Serializable data class EntryDetail(val group: String, val entry: String) : NavKey

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
                    OverviewScreen(
                        viewModel {
                            OverviewViewModel(
                                graph.groups,
                                GroupId.parse(key.group),
                                graph::identifier,
                                graph::today,
                                graph::now,
                                graph.scope,
                            )
                        },
                        deleteRequested = deleting?.let(EntryId::parse),
                        onDeleteTaken = { deleting = null },
                        onBack = { pop() },
                        onNew = { stack.add(EntryEditor(key.group)) },
                        onOpen = { stack.add(EntryDetail(key.group, it.hex)) },
                        onEdit = { stack.add(EntryEditor(key.group, it.hex)) },
                        onDuplicate = {
                            stack.add(EntryEditor(key.group, it.hex, duplicate = true))
                        },
                    )
                }
                entry<EntryEditor> { key ->
                    EntryEditorScreen(
                        viewModel {
                            EntryEditorViewModel(
                                graph.groups,
                                GroupId.parse(key.group),
                                key.entry?.let(EntryId::parse),
                                key.duplicate,
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
                        onDelete = {
                            pop()
                            deleting = key.entry
                        },
                    )
                }
            },
    )
}
