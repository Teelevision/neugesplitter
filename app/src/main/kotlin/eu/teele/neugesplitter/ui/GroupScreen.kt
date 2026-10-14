package eu.teele.neugesplitter.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.em
import eu.teele.neugesplitter.R

/** The destinations inside a group (ui-design 1). */
enum class Destination(@param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    OVERVIEW(R.drawable.ic_list, R.string.destination_overview),
    PEOPLE(R.drawable.ic_people, R.string.destination_people),
    ACTIVITY(R.drawable.ic_activity, R.string.destination_activity),
    SETTINGS(R.drawable.ic_settings, R.string.destination_settings),

    /** Opened from the overview, and under it in the navigation bar (TD-1). */
    TODOS(R.drawable.ic_checklist, R.string.todos),
}

/**
 * A group's destinations under one navigation bar, with the new-entry action docked in its centre
 * (ui-design 1); on the todo list it adds a todo instead (TD-1). Switching destinations never
 * leaves the group, and back returns to the overview before it does.
 *
 * @param onBack leaves the group, from the overview.
 * @param onNew the centre action, on the destination shown.
 * @param attention whether Activity holds something to look at: unseen changes, a halted sync or
 *   suggestions waiting (ui-design 4, RE-10).
 * @param content the destination, given what its back arrow does and how to switch to another; it
 *   must apply the modifier it is given.
 */
@Composable
fun GroupScreen(
    onBack: () -> Unit,
    onNew: (Destination) -> Unit,
    attention: Boolean,
    content: @Composable (Destination, () -> Unit, (Destination) -> Unit, Modifier) -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.OVERVIEW) }
    BackHandler(destination != Destination.OVERVIEW) { destination = Destination.OVERVIEW }
    val back = {
        if (destination == Destination.OVERVIEW) {
            onBack()
        } else {
            destination = Destination.OVERVIEW
        }
    }
    Scaffold(
        bottomBar = {
            GroupBar(destination, { destination = it }, { onNew(destination) }, attention)
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        content(
            destination,
            back,
            { destination = it },
            Modifier.padding(padding).consumeWindowInsets(padding),
        )
    }
}

@Composable
private fun GroupBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    onNew: () -> Unit,
    attention: Boolean,
) {
    val colours = MaterialTheme.colorScheme
    // Marked by hue rather than brightness, which this palette cannot separate (ui-design 8).
    val itemColours =
        NavigationBarItemDefaults.colors(
            selectedIconColor = colours.primary,
            selectedTextColor = colours.primary,
            indicatorColor = colours.primaryContainer,
            unselectedIconColor = colours.onSurfaceVariant,
            unselectedTextColor = colours.onSurfaceVariant,
        )
    NavigationBar {
        // The todo list belongs to the overview it is opened from.
        val shown = if (selected == Destination.TODOS) Destination.OVERVIEW else selected
        DestinationItem(Destination.OVERVIEW, shown, onSelect, itemColours)
        DestinationItem(Destination.PEOPLE, shown, onSelect, itemColours)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            FloatingActionButton(
                onClick = onNew,
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    stringResource(
                        if (selected == Destination.TODOS) R.string.todo_new else R.string.new_entry
                    ),
                )
            }
        }
        DestinationItem(Destination.ACTIVITY, shown, onSelect, itemColours, attention)
        DestinationItem(Destination.SETTINGS, shown, onSelect, itemColours)
    }
}

@Composable
private fun RowScope.DestinationItem(
    destination: Destination,
    selected: Destination,
    onSelect: (Destination) -> Unit,
    colours: NavigationBarItemColors,
    badged: Boolean = false,
) {
    NavigationBarItem(
        selected = destination == selected,
        onClick = { onSelect(destination) },
        icon = {
            BadgedBox(
                badge = {
                    if (badged) {
                        val label = stringResource(R.string.needs_attention)
                        Badge(Modifier.semantics { contentDescription = label })
                    }
                }
            ) {
                Icon(painterResource(destination.icon), contentDescription = null)
            }
        },
        label = {
            // A label shrinks where it would otherwise be cut off or break mid-word, as
            // "Einstellungen" does even at the normal text size on a 360dp phone, but by no more
            // than a fifth below normal (UX-5, UX-6). Sizes in sp scale with the text size
            // themselves, so the floor divides it back out, and the spacing is in em so that it
            // shrinks along with the letters.
            val size = LocalTextStyle.current.fontSize
            Text(
                stringResource(destination.label),
                maxLines = 1,
                letterSpacing = 0.04.em,
                autoSize =
                    TextAutoSize.StepBased(
                        minFontSize = size / LocalDensity.current.fontScale * 0.8f,
                        maxFontSize = size,
                    ),
            )
        },
        colors = colours,
    )
}
