package com.andharness.ru.ui.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.ui.components.CenterMessage
import com.andharness.ru.ui.components.searchFieldColors
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * «Что агент помнит» — the user-facing view of the agent's long-term memory (`kind = "memory"`).
 *
 * Content is re-skinned to the unified internal-page style: the explainer is a [SettingsSection]
 * with muted [SettingsHelperText], and each stored memory is a rounded [Surface] card instead of a
 * flat row separated by a hairline divider. Spacing (16 dp gutters, 8 dp gaps, `shapes.large`
 * surfaces) and the `bodyLarge` / `bodySmall` typography match the Settings redesign. Behavior is
 * unchanged.
 *
 * On top of the shared structure this screen adds a search field and a destructive «Забыть всё»
 * action. Deletion is permanent, so the single-row trash acts immediately (matching the app's agreed
 * "dangerous actions run immediately" decision) while the bulk «Забыть всё» — which can wipe the
 * whole memory at once — is gated behind an [AlertDialog] confirmation.
 *
 * The top bar is deliberately not animated; the explainer, search field and rows enter as one
 * `and-fade-up` cascade via [Modifier.enterAnimation] (motion kit D1), with an
 * [EnterAnimationTracker] so `LazyColumn` reuse during scroll does not replay the animation.
 *
 * References (official docs):
 * - Compose Material 3 components, `Scaffold`/`OutlinedTextField`/`AlertDialog`/`Surface` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Compose lists — https://developer.android.com/develop/ui/compose/lists
 * - Motion kit / `Modifier.enterAnimation` —
 *   https://developer.android.com/develop/ui/compose/animation/value-based
 * - `stringResource` for localized UI strings —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Подтверждение «забыть всё»: единственное разрушительное действие экрана, поэтому оно
    // проходит через диалог; удаление одной заметки остаётся мгновенным.
    var confirmForgetAll by remember { mutableStateOf(false) }

    // Трекер «уже анимированных» строк: каскад проигрывается один раз за визит экрана, а не при
    // переиспользовании элементов LazyColumn во время скролла (моушен-кит, D1).
    val rowTracker = remember { EnterAnimationTracker() }


    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.home_row_memory),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // `innerPadding` уже применил системные inset'ы (top bar/status/navigation bars).
                // Сообщаем дочерним модификаторам, что эта часть inset'ов израсходована, иначе
                // `imePadding()` добавит высоту navigation bar второй раз при открытии клавиатуры.
                // https://developer.android.com/develop/ui/compose/layouts/insets
                .consumeWindowInsets(innerPadding)
                // Клавиатура сдвигает вверх только контент; `topBar` лежит вне этого Column.
                .imePadding()
        ) {
            SettingsSection(
                title = stringResource(R.string.memory_section_about_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    // Вход интро-панели — первая ступень каскада (and-fade-up).
                    .enterAnimation(index = 0)
            ) {
                SettingsHelperText(stringResource(R.string.memory_intro))
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.memory_search_label)) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.memory_search_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = searchFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .enterAnimation(
                        index = 1,
                        tracker = rowTracker,
                        itemKey = "memory_search"
                    )
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val memories = state.visibleMemories
                when {
                    state.isLoading && state.memories.isEmpty() ->
                        CenterMessage(stringResource(R.string.memory_loading))

                    state.isSearchActive && state.isSearching && memories.isEmpty() ->
                        CenterMessage(stringResource(R.string.memory_searching))

                    memories.isEmpty() -> CenterMessage(
                        if (state.isSearchActive) {
                            stringResource(R.string.memory_empty_search)
                        } else {
                            stringResource(R.string.memory_empty)
                        }
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = memories,
                            key = { _, memory -> memory.id }
                        ) { index, memory ->
                            MemoryCard(
                                memory = memory,
                                onDelete = { viewModel.delete(memory.id) },
                                modifier = Modifier.enterAnimation(
                                    index = index + 2,
                                    tracker = rowTracker,
                                    itemKey = memory.id
                                )
                            )
                        }
                    }
                }
            }

            // Честная строка ошибки: появляется только когда последнее действие не удалось.
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Разрушительное действие доступно только когда память не пуста.
            if (state.memories.isNotEmpty()) {
                OutlinedButton(
                    onClick = { confirmForgetAll = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.memory_forget_all))
                }
            }
        }
    }

    if (confirmForgetAll) {
        AlertDialog(
            onDismissRequest = { confirmForgetAll = false },
            title = { Text(stringResource(R.string.memory_forget_dialog_title)) },
            text = { Text(stringResource(R.string.memory_forget_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForgetAll = false
                    viewModel.forgetAll()
                }) {
                    Text(stringResource(R.string.memory_forget_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForgetAll = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}


/**
 * One memory as a rounded card: its text (up to four lines, so long facts stay readable), a compact
 * `#id · важность · теги` metadata line from [memoryMetaText], and the trash control in the `error`
 * role. The 48 dp touch target keeps the destructive control at the M3 minimum hit size, and the
 * card surface matches the unified internal-page style.
 */
@Composable
private fun MemoryCard(
    memory: NoteEntity,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deleteLabel = stringResource(R.string.memory_delete_one)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = memory.text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = memoryMetaText(memory.id, memory.importance, memory.tags),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = deleteLabel }
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
