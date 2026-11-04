package eu.teele.neugesplitter.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier

/** Lets a snackbar be dismissed with a horizontal swipe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDismissSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> key(data) { DismissibleSnackbar(data) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissibleSnackbar(data: SnackbarData) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) data.dismiss()
    }
    SwipeToDismissBox(state = state, backgroundContent = {}) { Snackbar(data) }
}
