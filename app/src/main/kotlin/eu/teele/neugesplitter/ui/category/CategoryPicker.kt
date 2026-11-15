package eu.teele.neugesplitter.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.category.Category
import eu.teele.neugesplitter.domain.value.CategoryId
import java.text.Collator

/** How many chosen categories [ChosenCategories] names before it counts the rest. */
private const val NAMED = 3

/**
 * Chooses among a group's [categories], however many there are (EN-10): the [ranked] ones first,
 * most used first and each with its figure, then the others folded away, and a search through all
 * of them. Null stands for no category, which leads. With [single] a tap chooses and closes; else
 * each tap adds or removes one, up to [limit], and [marker] marks the chosen by their place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(
    categories: List<Category>,
    ranked: List<Pair<CategoryId?, String?>>,
    selected: List<CategoryId?>,
    onChange: (List<CategoryId?>) -> Unit,
    onDismiss: () -> Unit,
    single: Boolean = false,
    limit: Int = Int.MAX_VALUE,
    marker: ((Int) -> Color)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var unfolded by rememberSaveable { mutableStateOf(false) }
    val collator = Collator.getInstance(LocalConfiguration.current.locales[0])
    val known = categories.associateBy { it.id }
    val names = categories.associate { it.id to categoryName(it) }
    val labels = categories.associate { it.id to "${it.emoji} ${names.getValue(it.id)}" }
    val none = stringResource(R.string.category_none)
    val figures = ranked.toMap()
    val used = ranked.mapNotNull { it.first }.filter { it in known }
    val others =
        categories
            .filter { it.id !in figures && (!it.deleted || it.id in selected) }
            .sortedWith(compareBy(collator) { names.getValue(it.id) })
            .map { it.id }
    val search = query.trim()
    val shown: List<CategoryId?> =
        when {
            search.isNotEmpty() ->
                (listOf(null) + used + others).filter {
                    (if (it == null) none else labels.getValue(it)).contains(search, true)
                }
            unfolded || used.isEmpty() -> listOf(null) + used + others
            else -> listOf(null) + used
        }
    val folded = if (search.isEmpty() && !unfolded && used.isNotEmpty()) others.size else 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.category_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (limit != Int.MAX_VALUE) {
                Text(
                    stringResource(R.string.category_limit, limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(shown, key = { it?.hex.orEmpty() }) { id ->
                    val place = selected.indexOf(id)
                    val chosen = place >= 0
                    Option(
                        label = if (id == null) none else labels.getValue(id),
                        figure = figures[id],
                        deleted = id != null && known.getValue(id).deleted,
                        chosen = chosen,
                        single = single,
                        enabled = chosen || single || selected.size < limit,
                        marker = marker?.takeIf { chosen }?.invoke(place),
                    ) {
                        if (single) {
                            onChange(listOf(id))
                            onDismiss()
                        } else onChange(if (chosen) selected - id else selected + id)
                    }
                }
                if (folded > 0) {
                    item(key = "more") {
                        ListItem(
                            headlineContent = {
                                Text(
                                    pluralStringResource(R.plurals.category_more, folded, folded),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.clickable { unfolded = true },
                        )
                    }
                }
            }
            if (!single) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onChange(emptyList()) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.filter_clear))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

@Composable
private fun Option(
    label: String,
    figure: String?,
    deleted: Boolean,
    chosen: Boolean,
    single: Boolean,
    enabled: Boolean,
    marker: Color?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                label,
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
                color =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (single) RadioButton(selected = chosen, onClick = null)
                else Checkbox(checked = chosen, onCheckedChange = null, enabled = enabled)
                if (marker != null) Dot(marker, Modifier.padding(start = 8.dp))
            }
        },
        trailingContent = figure?.let { { Text(it) } },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * The categories [selected] as chips that remove them, the first few by name, and a chip that opens
 * the [CategorySheet]; [empty] says what nothing chosen means.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChosenCategories(
    categories: List<Category>,
    selected: List<CategoryId?>,
    empty: String,
    onRemove: (CategoryId?) -> Unit,
    onChoose: () -> Unit,
    marker: ((Int) -> Color)? = null,
) {
    val known = categories.associateBy { it.id }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.take(NAMED).forEachIndexed { place, id ->
            val category = id?.let(known::get)
            InputChip(
                selected = false,
                onClick = { onRemove(id) },
                label = {
                    Text(
                        category?.let { "${it.emoji} ${categoryName(it)}" }
                            ?: stringResource(R.string.category_none)
                    )
                },
                leadingIcon = marker?.let { { Dot(it(place)) } },
                trailingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        stringResource(R.string.category_remove),
                        Modifier.size(18.dp),
                    )
                },
            )
        }
        if (selected.size > NAMED) {
            AssistChip(onClick = onChoose, label = { Text("+${selected.size - NAMED}") })
        }
        AssistChip(
            onClick = onChoose,
            label = {
                Text(if (selected.isEmpty()) empty else stringResource(R.string.category_choose))
            },
        )
    }
}

@Composable
private fun Dot(colour: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(colour))
}
