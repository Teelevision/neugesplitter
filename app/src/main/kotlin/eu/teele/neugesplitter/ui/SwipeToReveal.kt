package eu.teele.neugesplitter.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import kotlin.math.roundToInt

/** How wide one action behind a row is. */
val SWIPE_ACTION = 88.dp

/** Pixels a second that open the actions however little the row moved. */
private const val FLING = 1_000f

/**
 * [content] that a swipe to the left moves aside by [revealed], showing [actions] behind it, each
 * given a way to close the row again. Only the left swipe reveals, so that a scroll cannot trigger
 * an action (ui-design 4).
 */
@Composable
fun SwipeToReveal(
    revealed: Dp,
    actions: @Composable RowScope.(close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val width = with(LocalDensity.current) { revealed.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { offset = (offset + it).coerceIn(-width, 0f) }
    Box {
        if (offset < 0f) {
            Row(
                modifier = Modifier.matchParentSize(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions { offset = 0f }
            }
        }
        Box(
            Modifier.offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    drag,
                    Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val open = offset < -width / 2 || velocity < -FLING
                        animate(offset, if (open) -width else 0f) { value, _ -> offset = value }
                    },
                )
        ) {
            content()
        }
    }
}

/** The delete button behind a row, on the error colour. */
@Composable
fun DeleteAction(onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.width(SWIPE_ACTION).fillMaxHeight().background(colours.errorContainer),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.width(SWIPE_ACTION).fillMaxHeight()) {
            Icon(
                painterResource(R.drawable.ic_delete),
                stringResource(R.string.delete),
                tint = colours.onErrorContainer,
            )
        }
    }
}
