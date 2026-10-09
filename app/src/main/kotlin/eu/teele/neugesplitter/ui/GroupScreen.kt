package eu.teele.neugesplitter.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import eu.teele.neugesplitter.R

/** The destinations inside a group (ui-design 1). Activity is still to come. */
enum class Destination(@param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    OVERVIEW(R.drawable.ic_list, R.string.destination_overview),
    PEOPLE(R.drawable.ic_people, R.string.destination_people),
    SETTINGS(R.drawable.ic_settings, R.string.destination_settings),
}

/**
 * A group's destinations under one navigation bar, with the new-entry action docked in its centre
 * (ui-design 1). Switching destinations never leaves the group, and back returns to the overview
 * before it does.
 *
 * @param onBack leaves the group, from the overview.
 * @param content the destination, given what its back arrow does; it must apply the modifier it is
 *   given.
 */
@Composable
fun GroupScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    content: @Composable (Destination, () -> Unit, Modifier) -> Unit,
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
        bottomBar = { GroupBar(destination, { destination = it }, onNew) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        content(destination, back, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

@Composable
private fun GroupBar(selected: Destination, onSelect: (Destination) -> Unit, onNew: () -> Unit) {
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
        DestinationItem(Destination.OVERVIEW, selected, onSelect, itemColours)
        DestinationItem(Destination.PEOPLE, selected, onSelect, itemColours)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            FloatingActionButton(
                onClick = onNew,
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
            ) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.new_entry))
            }
        }
        // Where Activity will go, so that the new-entry action stays in the centre.
        Spacer(Modifier.weight(1f))
        DestinationItem(Destination.SETTINGS, selected, onSelect, itemColours)
    }
}

@Composable
private fun RowScope.DestinationItem(
    destination: Destination,
    selected: Destination,
    onSelect: (Destination) -> Unit,
    colours: NavigationBarItemColors,
) {
    NavigationBarItem(
        selected = destination == selected,
        onClick = { onSelect(destination) },
        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
        label = { Text(stringResource(destination.label)) },
        colors = colours,
    )
}
