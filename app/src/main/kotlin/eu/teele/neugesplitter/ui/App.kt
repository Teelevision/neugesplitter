package eu.teele.neugesplitter.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import eu.teele.neugesplitter.AppGraph
import eu.teele.neugesplitter.domain.value.GroupId
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

/** Every screen, and how one leads to the next (ui-design 2). */
@Composable
fun App(graph: AppGraph) {
    val stack = rememberNavBackStack(GroupList)
    val pop = { stack.removeLastOrNull() }
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
                            OverviewViewModel(graph.groups, GroupId.parse(key.group), graph::today)
                        },
                        onBack = { pop() },
                    )
                }
            },
    )
}
