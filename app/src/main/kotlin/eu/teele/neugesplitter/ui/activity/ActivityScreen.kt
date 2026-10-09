package eu.teele.neugesplitter.ui.activity

import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.history.Happened
import eu.teele.neugesplitter.domain.history.Happening
import eu.teele.neugesplitter.domain.history.Revision
import eu.teele.neugesplitter.domain.history.Setting
import eu.teele.neugesplitter.domain.value.EntryId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.settings.SyncState
import eu.teele.neugesplitter.ui.UnseenDot
import eu.teele.neugesplitter.ui.sync.SyncHaltedCard
import eu.teele.neugesplitter.ui.sync.failed
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What changed in a group, by whom and when (HI-2), with what has not been seen yet highlighted
 * (HI-5, HI-7), and how its sync stands, with a manual sync (SY-6, SY-9, TR-12). Each part folds
 * away (ui-design 4).
 *
 * @param onOpen opens an entry a change names.
 * @param onHistory opens the history of a deleted entry a change names.
 * @param onShare starts sharing a group kept only on this device.
 * @param onSyncProblem opens why the group stopped syncing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    onBack: () -> Unit,
    onOpen: (EntryId) -> Unit,
    onHistory: (EntryId) -> Unit,
    onShare: () -> Unit,
    onSyncProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    var history by rememberSaveable { mutableStateOf(true) }
    var sync by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }

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
                actions = {
                    if (state.title == null) return@TopAppBar
                    state.sync?.let {
                        val (icon, label) = cloud(it)
                        Icon(
                            painterResource(icon),
                            stringResource(label),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
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
                                text = { Text(stringResource(R.string.mark_all_read)) },
                                enabled = state.unseen.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    viewModel.readAll()
                                },
                            )
                        }
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
            section("history", R.string.history, history) { history = !history }
            if (history) {
                itemsIndexed(state.history, key = { index, _ -> "happened-$index" }) {
                    index,
                    happening ->
                    HappeningRow(
                        happening,
                        index in state.unseen,
                        state,
                        locale,
                        { viewModel.read(index) },
                        onOpen,
                        onHistory,
                    )
                }
            }
            section("sync", R.string.sync, sync) { sync = !sync }
            if (sync) {
                item(key = "sync-state") {
                    Sync(state.sync, state.now, locale, viewModel::sync, onShare, onSyncProblem)
                }
            }
        }
    }
}

private fun LazyListScope.section(
    key: String,
    @StringRes title: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    item(key = key) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggle) {
                    Icon(
                        painterResource(
                            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                        ),
                        stringResource(if (expanded) R.string.collapse else R.string.expand),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

/** One change; tapping it reads it, and opens the entry it names if it names one (HI-3, HI-5). */
@Composable
private fun HappeningRow(
    happening: Happening,
    unseen: Boolean,
    state: ActivityState,
    locale: Locale,
    onRead: () -> Unit,
    onOpen: (EntryId) -> Unit,
    onHistory: (EntryId) -> Unit,
) {
    val entry = (happening.happened as? Happened.Entry)?.entry
    val text =
        stringResource(
            R.string.happened_at,
            sentence(happening, state.names),
            ago(happening.at, state.now, locale),
        )
    ListItem(
        headlineContent = { Text(text, fontWeight = if (unseen) FontWeight.SemiBold else null) },
        trailingContent = if (unseen) ({ UnseenDot() }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            if (entry == null && !unseen) Modifier
            else
                Modifier.clickable {
                    if (unseen) onRead()
                    if (entry != null) {
                        if (entry in state.deleted) onHistory(entry) else onOpen(entry)
                    }
                },
    )
}

/** What [happening] did, as a sentence naming who did it (HI-2). */
@Composable
private fun sentence(happening: Happening, names: Map<MemberId, String>): String {
    fun name(member: MemberId) = names[member] ?: member.hex.take(8)
    val by = name(happening.by)
    return when (val happened = happening.happened) {
        Happened.Created -> stringResource(R.string.happened_created, by)
        is Happened.Changed ->
            stringResource(
                when (happened.settings.singleOrNull()) {
                    Setting.NAME -> R.string.happened_name
                    Setting.DESCRIPTION -> R.string.happened_description
                    Setting.EMOJI -> R.string.happened_emoji
                    Setting.COLOUR -> R.string.happened_colour
                    Setting.DEFAULT_PARTICIPANTS -> R.string.happened_participants
                    Setting.SCALE -> R.string.happened_scale
                    null -> R.string.happened_settings
                },
                by,
            )
        is Happened.MemberAdded ->
            if (happened.member == happening.by) stringResource(R.string.happened_joined, by)
            else stringResource(R.string.happened_added, by, name(happened.member))
        is Happened.MemberRenamed ->
            stringResource(R.string.happened_renamed, by, happened.from, happened.to)
        is Happened.MemberRemoved ->
            stringResource(R.string.happened_removed, by, name(happened.member))
        is Happened.Entry ->
            stringResource(
                when (happened.change) {
                    Revision.CREATED -> R.string.happened_entry_created
                    Revision.EDITED -> R.string.happened_entry_edited
                    Revision.DELETED -> R.string.happened_entry_deleted
                    Revision.RESTORED -> R.string.happened_entry_restored
                },
                by,
                happened.description,
            )
        Happened.Recurring -> stringResource(R.string.happened_recurring, by)
    }
}

/** How the group's sync stands (SY-6), or that it is kept only here; null [sync] is the latter. */
@Composable
private fun Sync(
    sync: SyncState?,
    now: Instant,
    locale: Locale,
    onSync: () -> Unit,
    onShare: () -> Unit,
    onSyncProblem: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            sync == null -> {
                Text(
                    stringResource(R.string.storage_local),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.sync_local_text), color = colours.onSurfaceVariant)
                OutlinedButton(onClick = onShare) { Text(stringResource(R.string.storage_set_up)) }
            }
            sync.halted != null -> SyncHaltedCard(onSyncProblem)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(
                            if (sync.failure == null) R.drawable.ic_check else R.drawable.ic_warning
                        ),
                        null,
                        tint = if (sync.failure == null) colours.primary else colours.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        sync.succeeded?.let {
                            stringResource(R.string.sync_succeeded, ago(it, now, locale))
                        } ?: stringResource(R.string.sync_never),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                sync.failure?.let { failure ->
                    val (title, text) = failed(failure)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title), color = colours.error)
                        Text(stringResource(text), color = colours.onSurfaceVariant)
                    }
                }
                Text(
                    if (sync.waiting > 0) {
                        val waiting = sync.waiting.toInt()
                        pluralStringResource(R.plurals.sync_waiting, waiting, waiting)
                    } else stringResource(R.string.sync_uploaded),
                    color = colours.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onSync) { Text(stringResource(R.string.sync_now)) }
            }
        }
    }
}

/** The icon and its label for how [sync] stands, for the top bar. */
private fun cloud(sync: SyncState): Pair<Int, Int> =
    when {
        sync.halted != null || sync.failure != null ->
            R.drawable.ic_cloud_off to R.string.sync_problem
        sync.waiting > 0 -> R.drawable.ic_cloud_upload to R.string.sync_pending
        else -> R.drawable.ic_cloud_done to R.string.sync_in_sync
    }

/**
 * How long before [now] [at] was, as [locale] says it: minutes, hours, yesterday, days, then the
 * date.
 */
private fun ago(at: Instant, now: Instant, locale: Locale): String {
    val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
    val zone = ZoneId.systemDefault()
    val elapsed = Duration.between(at, now)
    val days =
        ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        elapsed.toMinutes() < 1 -> formatter.format(Direction.PLAIN, AbsoluteUnit.NOW)
        elapsed.toHours() < 1 ->
            formatter.format(elapsed.toMinutes().toDouble(), Direction.LAST, RelativeUnit.MINUTES)
        days < 1 ->
            formatter.format(elapsed.toHours().toDouble(), Direction.LAST, RelativeUnit.HOURS)
        days == 1L -> formatter.format(Direction.LAST, AbsoluteUnit.DAY)
        days < 7 -> formatter.format(days.toDouble(), Direction.LAST, RelativeUnit.DAYS)
        else ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(at.atZone(zone))
    }
}
