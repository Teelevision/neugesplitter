package eu.teele.neugesplitter.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.domain.value.SeriesId
import eu.teele.neugesplitter.ui.ChoiceDialog
import eu.teele.neugesplitter.ui.ColourPicker
import eu.teele.neugesplitter.ui.EmojiPicker
import eu.teele.neugesplitter.ui.InlineEditor
import eu.teele.neugesplitter.ui.editor.recurrenceText
import eu.teele.neugesplitter.ui.longDate
import eu.teele.neugesplitter.ui.sync.GroupTitle
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private enum class Dialog {
    PARTICIPANTS,
    INTERVAL,
    DELETE,
}

/** What a group may poll at most every (SY-13); the shortest is Android's own (TR-10). */
private val INTERVALS = listOf(15.minutes, 1.hours, 6.hours, 24.hours)

/** A row edited in place of itself. */
private enum class Field {
    NAME,
    DESCRIPTION,
}

/**
 * A group's name, look and default participants, its recurring series, where it is kept, and how it
 * is removed from this device (ui-design 4, GR-2, SP-8, RE-7, GR-11). Name and description are
 * edited in place; the look takes effect as soon as it is tapped.
 *
 * @param onDeleted called once the group is gone from this device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    viewModel: GroupSettingsViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens backend setup: to share the group, or to give its location a new secret. */
    onBackend: () -> Unit = {},
    /** Opens a recurring series (RE-7). */
    onSeries: (SeriesId) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    var editing by rememberSaveable { mutableStateOf<Field?>(null) }
    var stopped by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]

    // Deleted while open, which this screen or another can do.
    LaunchedEffect(state) { if (state.loaded && state.title == null) onBack() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { GroupTitle(state.title.orEmpty(), state.sync) },
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
            item(key = "name") {
                if (editing == Field.NAME) {
                    InlineEditor(
                        state.name,
                        stringResource(R.string.group_name),
                        onSave = {
                            viewModel.rename(it)
                            editing = null
                        },
                        onCancel = { editing = null },
                    )
                } else {
                    SettingRow(
                        stringResource(R.string.group_name),
                        state.name,
                        onClick = { editing = Field.NAME },
                    )
                }
            }
            item(key = "description") {
                if (editing == Field.DESCRIPTION) {
                    InlineEditor(
                        state.description.orEmpty(),
                        stringResource(R.string.group_description),
                        onSave = {
                            viewModel.describe(it)
                            editing = null
                        },
                        onCancel = { editing = null },
                        // A blank description is none, so it can always be saved.
                        allowBlank = true,
                        singleLine = false,
                        capitalization = KeyboardCapitalization.Sentences,
                    )
                } else {
                    SettingRow(
                        stringResource(R.string.group_description),
                        state.description ?: stringResource(R.string.none),
                        onClick = { editing = Field.DESCRIPTION },
                    )
                }
            }
            item(key = "emoji") {
                Looks(R.string.group_emoji) { EmojiPicker(state.emoji, viewModel::emoji) }
            }
            item(key = "colour") {
                Looks(R.string.group_colour) { ColourPicker(state.colour, viewModel::colour) }
            }
            item(key = "participants") {
                SettingRow(
                    stringResource(R.string.default_participants),
                    participants(state),
                    onClick = { dialog = Dialog.PARTICIPANTS },
                )
            }
            item(key = "series") {
                Text(
                    stringResource(R.string.series_list),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                )
            }
            if (state.series.isEmpty() && state.stopped.isEmpty()) {
                item(key = "series-none") {
                    Text(
                        stringResource(R.string.series_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.series, key = { "series-${it.id.hex}" }) {
                SeriesRow(it, stopped = false, locale, onClick = { onSeries(it.id) })
            }
            if (state.stopped.isNotEmpty()) {
                item(key = "stopped") {
                    ListItem(
                        headlineContent = {
                            Text(
                                pluralStringResource(
                                    R.plurals.series_stopped_count,
                                    state.stopped.size,
                                    state.stopped.size,
                                )
                            )
                        },
                        trailingContent = {
                            Icon(
                                painterResource(
                                    if (stopped) R.drawable.ic_expand_less
                                    else R.drawable.ic_expand_more
                                ),
                                stringResource(if (stopped) R.string.collapse else R.string.expand),
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { stopped = !stopped },
                    )
                }
                if (stopped) {
                    items(state.stopped, key = { "stopped-${it.id.hex}" }) {
                        SeriesRow(it, stopped = true, locale, onClick = { onSeries(it.id) })
                    }
                }
            }
            val backend = state.backend
            if (backend == null) {
                item(key = "local") {
                    SettingRow(
                        stringResource(R.string.storage_local),
                        stringResource(R.string.storage_local_warning),
                    )
                }
                item(key = "share") {
                    SettingRow(
                        stringResource(R.string.storage_set_up),
                        supporting = null,
                        onClick = onBackend,
                        headlineColour = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                item(key = "shared") {
                    SettingRow(
                        stringResource(R.string.storage_shared),
                        stringResource(
                            R.string.storage_shared_summary,
                            backend.host,
                            backend.bucket,
                        ),
                        onClick = onBackend,
                    )
                }
                item(key = "encryption") {
                    SettingRow(
                        stringResource(R.string.encryption),
                        stringResource(
                            if (backend.encrypted) R.string.encryption_on
                            else R.string.encryption_off
                        ),
                    )
                }
                item(key = "interval") {
                    SettingRow(
                        stringResource(R.string.poll_interval),
                        atMostEvery(backend.interval),
                        onClick = { dialog = Dialog.INTERVAL },
                    )
                }
            }
            item(key = "delete") {
                SettingRow(
                    stringResource(R.string.delete_group_device),
                    supporting = null,
                    onClick = { dialog = Dialog.DELETE },
                    headlineColour = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        Dialog.PARTICIPANTS ->
            ParticipantsDialog(
                state.members,
                state.defaultParticipants,
                onSave = {
                    viewModel.defaultParticipants(it)
                    close()
                },
                onCancel = close,
            )
        Dialog.INTERVAL ->
            state.backend?.let { backend ->
                ChoiceDialog(
                    R.string.poll_interval,
                    INTERVALS.map { it to atMostEvery(it) },
                    backend.interval,
                    onChoose = {
                        viewModel.interval(it)
                        close()
                    },
                    onCancel = close,
                    note = stringResource(R.string.poll_interval_note),
                )
            }
        Dialog.DELETE ->
            AlertDialog(
                onDismissRequest = close,
                title = { Text(pluralStringResource(R.plurals.delete_groups_title, 1, 1)) },
                text = { Text(pluralStringResource(R.plurals.delete_groups_text, 1)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            close()
                            viewModel.delete(onDeleted)
                        }
                    ) {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
                },
            )
        null -> Unit
    }
}

/** Everyone, or the names of those new entries start with. */
@Composable
private fun participants(state: GroupSettingsState): String {
    val chosen = state.defaultParticipants ?: return stringResource(R.string.everyone)
    val names = state.members.filter { it.id in chosen }.joinToString(", ") { it.name }
    return names.ifEmpty { stringResource(R.string.none) }
}

/** A poll interval as the bound it is, not a promise (TR-10). */
@Composable
private fun atMostEvery(interval: Duration): String {
    val hours = interval.inWholeHours.toInt()
    if (interval == hours.hours) return pluralStringResource(R.plurals.poll_hours, hours, hours)
    val minutes = interval.inWholeMinutes.toInt()
    return pluralStringResource(R.plurals.poll_minutes, minutes, minutes)
}

/** A series, how it repeats, and when it stops or stopped (RE-7). */
@Composable
private fun SeriesRow(
    series: SeriesSummary,
    stopped: Boolean,
    locale: Locale,
    onClick: () -> Unit,
) {
    SettingRow(
        series.description,
        listOfNotNull(
                recurrenceText(series.interval, locale),
                series.stop?.let {
                    stringResource(
                        if (stopped) R.string.series_stopped else R.string.series_stops,
                        longDate(it, locale),
                    )
                },
            )
            .joinToString(" · "),
        onClick = onClick,
    )
}

/** A label and its value; a row with no [onClick] only informs. */
@Composable
private fun SettingRow(
    headline: String,
    supporting: String?,
    onClick: (() -> Unit)? = null,
    headlineColour: Color = Color.Unspecified,
) {
    ListItem(
        headlineContent = { Text(headline, color = headlineColour) },
        supportingContent = supporting?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Looks(label: Int, picker: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        picker()
    }
}

/** A checkbox per member; at least one must stay checked (SP-8). */
@Composable
private fun ParticipantsDialog(
    members: List<Member>,
    current: List<MemberId>?,
    onSave: (Set<MemberId>) -> Unit,
    onCancel: () -> Unit,
) {
    var checked by rememberSaveable {
        mutableStateOf(members.filter { current == null || it.id in current }.map { it.id.hex })
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.default_participants)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (member in members) {
                    val on = member.id.hex in checked
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .toggleable(
                                    value = on,
                                    onValueChange = {
                                        checked =
                                            if (it) checked + member.id.hex
                                            else checked - member.id.hex
                                    },
                                    role = Role.Checkbox,
                                )
                                .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(member.name, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(members.filter { it.id.hex in checked }.map { it.id }.toSet()) },
                enabled = checked.isNotEmpty(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
