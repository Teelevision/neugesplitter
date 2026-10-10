package eu.teele.neugesplitter.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.ui.activity.ActivityScreen
import eu.teele.neugesplitter.ui.activity.ActivityViewModel
import eu.teele.neugesplitter.ui.appsettings.AppSettingsScreen
import eu.teele.neugesplitter.ui.appsettings.AppSettingsViewModel
import eu.teele.neugesplitter.ui.backend.BackendSetupScreen
import eu.teele.neugesplitter.ui.backend.BackendSetupViewModel
import eu.teele.neugesplitter.ui.backend.Location
import eu.teele.neugesplitter.ui.detail.EntryDetailScreen
import eu.teele.neugesplitter.ui.detail.EntryDetailViewModel
import eu.teele.neugesplitter.ui.editor.EntryEditorScreen
import eu.teele.neugesplitter.ui.editor.EntryEditorViewModel
import eu.teele.neugesplitter.ui.grouplist.GroupListScreen
import eu.teele.neugesplitter.ui.grouplist.GroupListViewModel
import eu.teele.neugesplitter.ui.history.EntryHistoryScreen
import eu.teele.neugesplitter.ui.history.EntryHistoryViewModel
import eu.teele.neugesplitter.ui.join.JoinScreen
import eu.teele.neugesplitter.ui.join.JoinViewModel
import eu.teele.neugesplitter.ui.licences.LicenceScreen
import eu.teele.neugesplitter.ui.licences.LicencesScreen
import eu.teele.neugesplitter.ui.newgroup.NewGroupScreen
import eu.teele.neugesplitter.ui.newgroup.NewGroupViewModel
import eu.teele.neugesplitter.ui.overview.OverviewScreen
import eu.teele.neugesplitter.ui.overview.OverviewViewModel
import eu.teele.neugesplitter.ui.people.PeopleScreen
import eu.teele.neugesplitter.ui.people.PeopleViewModel
import eu.teele.neugesplitter.ui.series.SeriesScreen
import eu.teele.neugesplitter.ui.series.SeriesViewModel
import eu.teele.neugesplitter.ui.settings.GroupSettingsScreen
import eu.teele.neugesplitter.ui.settings.GroupSettingsViewModel
import eu.teele.neugesplitter.ui.sync.SyncProblemScreen
import eu.teele.neugesplitter.ui.sync.SyncProblemViewModel
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable

@Serializable data object GroupList : NavKey

@Serializable data object NewGroup : NavKey

@Serializable data object JoinGroup : NavKey

@Serializable data object AppSettings : NavKey

@Serializable data object Licences : NavKey

/** @property name the notice's name. */
@Serializable data class Licence(val name: String) : NavKey

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

/** Ids are hex. */
@Serializable data class RecurringSeries(val group: String, val series: String) : NavKey

/** @property group the group's id, as hex. */
@Serializable data class BackendSetup(val group: String) : NavKey

/** @property group the group's id, as hex. */
@Serializable data class SyncProblem(val group: String) : NavKey

/**
 * An empty location joining found, for the new group to be shared at. It holds the secret key, so
 * it stays in memory and out of the saved navigation state.
 */
private class Handover {
    var location: Location? = null
}

/** Every screen, and how one leads to the next (ui-design 2). */
@Composable
fun App(graph: AppGraph) {
    val stack = rememberNavBackStack(GroupList)
    // Several screens may react to one deletion, so a pop can arrive when only the root is left.
    val pop = { if (stack.size > 1) stack.removeLastOrNull() }
    // An entry deleted from its detail screen, for the overview under it to offer undo for.
    var deleting by remember { mutableStateOf<String?>(null) }
    val handover = remember { Handover() }
    val focus = LocalFocusManager.current
    NavDisplay(
        backStack = stack,
        onBack = { pop() },
        // A tap nothing else takes, on the background, puts the keyboard away.
        modifier = Modifier.pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
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
                        onJoin = { stack.add(JoinGroup) },
                        onOpen = { stack.add(GroupOverview(it.hex)) },
                        onSettings = { stack.add(AppSettings) },
                    )
                }
                entry<AppSettings> {
                    val context = LocalContext.current
                    val version =
                        remember(context) {
                            context.packageManager
                                .getPackageInfo(context.packageName, 0)
                                .versionName
                                .orEmpty()
                        }
                    AppSettingsScreen(
                        viewModel {
                            AppSettingsViewModel(
                                graph.groups,
                                graph.preferences,
                                graph.languages,
                                graph.syncing::forgetAll,
                            )
                        },
                        version = version,
                        onBack = { pop() },
                        onLicences = { stack.add(Licences) },
                        onDeleted = { pop() },
                    )
                }
                entry<Licences> {
                    LicencesScreen(onBack = { pop() }, onOpen = { stack.add(Licence(it)) })
                }
                entry<Licence> { key -> LicenceScreen(key.name, onBack = { pop() }) }
                entry<NewGroup> {
                    NewGroupScreen(
                        viewModel {
                            NewGroupViewModel(
                                graph.groups,
                                graph::identifier,
                                graph::today,
                                graph::now,
                                shared = handover.location != null,
                            )
                        },
                        onClose = {
                            handover.location = null
                            pop()
                        },
                        onCreated = { id, shared ->
                            if (!shared) handover.location = null
                            pop()
                            stack.add(GroupOverview(id.hex))
                            if (shared) stack.add(BackendSetup(id.hex))
                        },
                    )
                }
                entry<JoinGroup> {
                    JoinScreen(
                        viewModel {
                            JoinViewModel(
                                graph.groups,
                                graph.stores,
                                graph.syncing,
                                graph::identifier,
                                graph::now,
                            )
                        },
                        onClose = { pop() },
                        onDone = {
                            pop()
                            stack.add(GroupOverview(it.hex))
                        },
                        onCreateHere = {
                            handover.location = it
                            pop()
                            stack.add(NewGroup)
                        },
                    )
                }
                entry<BackendSetup> { key ->
                    val locale = LocalConfiguration.current.locales[0]
                    BackendSetupScreen(
                        viewModel {
                            BackendSetupViewModel(
                                graph.groups,
                                GroupId.parse(key.group),
                                graph::today,
                                locale,
                                graph.stores,
                                graph.syncing::newKey,
                                graph.syncing::connect,
                                prefilled = handover.location.also { handover.location = null },
                            )
                        },
                        onClose = { pop() },
                        onDone = { pop() },
                    )
                }
                entry<GroupOverview> { key ->
                    val group = GroupId.parse(key.group)
                    val attention by
                        remember(group) {
                                combine(
                                    graph.groups.unseen(group),
                                    graph.groups.backends.state(group),
                                    graph.groups.pending(group, graph.today()),
                                ) { unseen, sync, pending ->
                                    unseen.isNotEmpty() || sync?.halted != null || pending > 0
                                }
                            }
                            .collectAsStateWithLifecycle(false)
                    GroupScreen(
                        onBack = { pop() },
                        onNew = { stack.add(EntryEditor(key.group)) },
                        attention = attention,
                    ) { destination, back, go, modifier ->
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
                                    onBack = back,
                                    onOpen = { stack.add(EntryDetail(key.group, it.hex)) },
                                    onEdit = { stack.add(EntryEditor(key.group, it.hex)) },
                                    onDuplicate = {
                                        stack.add(EntryEditor(key.group, it.hex, duplicate = true))
                                    },
                                    onHistory = { stack.add(EntryHistory(key.group, it.hex)) },
                                    onSyncProblem = { stack.add(SyncProblem(key.group)) },
                                    onActivity = { go(Destination.ACTIVITY) },
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
                                    onBack = back,
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
                                    modifier = modifier,
                                )
                            Destination.ACTIVITY ->
                                ActivityScreen(
                                    viewModel {
                                        ActivityViewModel(
                                            graph.groups,
                                            group,
                                            graph::identifier,
                                            graph::today,
                                            graph::now,
                                            graph.syncing::now,
                                        )
                                    },
                                    onBack = back,
                                    onOpen = { stack.add(EntryDetail(key.group, it.hex)) },
                                    onEdit = { stack.add(EntryEditor(key.group, it.hex)) },
                                    onHistory = { stack.add(EntryHistory(key.group, it.hex)) },
                                    onShare = { stack.add(BackendSetup(key.group)) },
                                    onSyncProblem = { stack.add(SyncProblem(key.group)) },
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
                                            graph.syncing::forget,
                                            graph.syncing::interval,
                                        )
                                    },
                                    onBack = back,
                                    onBackend = { stack.add(BackendSetup(key.group)) },
                                    onSeries = { stack.add(RecurringSeries(key.group, it.hex)) },
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
                                graph::today,
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
                        onSeries = { stack.add(RecurringSeries(key.group, it.hex)) },
                    )
                }
                entry<RecurringSeries> { key ->
                    SeriesScreen(
                        viewModel {
                            SeriesViewModel(
                                graph.groups,
                                GroupId.parse(key.group),
                                SeriesId.parse(key.series),
                                graph::identifier,
                                graph::today,
                                graph::now,
                            )
                        },
                        onBack = { pop() },
                        onOpen = { stack.add(EntryDetail(key.group, it.hex)) },
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
                entry<SyncProblem> { key ->
                    SyncProblemScreen(
                        viewModel {
                            SyncProblemViewModel(
                                graph.groups,
                                GroupId.parse(key.group),
                                graph.syncing::now,
                            )
                        },
                        onBack = { pop() },
                    )
                }
            },
    )
}
