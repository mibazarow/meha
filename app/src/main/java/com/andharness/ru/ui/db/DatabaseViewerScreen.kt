package com.andharness.ru.ui.db

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.data.db.viewer.DbRow
import com.andharness.ru.data.db.viewer.DbSection
import com.andharness.ru.data.db.viewer.DatabaseViewerSpec
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsClickRow
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * Read-only viewer for the app's internal Room database (update.md «Запланировано» → «Просмотр
 * базы данных»). The first screen lists human-readable sections (each table with its row count);
 * tapping a table opens a paginated, formatted list of its rows. By design there is **no** UI
 * affordance to add, edit or delete anything — mutations happen only through the agent in chat.
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign:
 * the explainer is a [SettingsSection] with muted [SettingsHelperText] and an «Открыть чаты»
 * [SettingsClickRow]; section titles use the theme `titleMedium`/SemiBold role and each table is a
 * rounded [Surface] card (`shapes.large`, `surfaceContainerLow`). Spacing (16 dp gutters, 8 dp gaps)
 * follows the shared kit. All fixed UI copy is localized.
 *
 * Note (follow-up): section/table labels (`section.title`, `ref.label`), the row-count text
 * ([DatabaseViewerSpec.totalCountText]) and `state.error` come from the viewer spec (data) / VM and
 * are still Russian-only.
 *
 * References (official docs):
 * - Material 3 `Scaffold` / `Surface` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Compose lists (`LazyColumn`) — https://developer.android.com/develop/ui/compose/lists
 * - `stringResource` — https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseViewerScreen(
    onBack: () -> Unit,
    onOpenChats: () -> Unit = {},
    viewModel: DatabaseViewerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // System back leaves the open table first, then the screen (mirrors the on-screen back button).
    BackHandler(enabled = state.selectedTable != null) { viewModel.closeTable() }

    Scaffold(
        topBar = {
            InternalTopBar(
                title = state.selectedTable?.let { state.selectedLabel }
                    ?: stringResource(R.string.home_row_database),
                onBack = {
                    if (state.selectedTable != null) viewModel.closeTable() else onBack()
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val error = state.error
            when {
                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )

                state.selectedTable == null ->
                    if (state.sections.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { LoadingOrEmpty(state.isLoading) }
                    } else {
                        SectionOverview(
                            sections = state.sections,
                            counts = state.counts,
                            onOpenTable = viewModel::openTable,
                            onOpenChats = onOpenChats
                        )
                    }

                state.isLoading && state.rows.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { LoadingOrEmpty(isLoading = true) }

                state.rows.isEmpty() -> Text(
                    text = stringResource(R.string.db_no_rows),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )

                else -> TableRows(
                    state = state,
                    onPrevious = viewModel::previousPage,
                    onNext = viewModel::nextPage
                )
            }
        }
    }
}

@Composable
private fun LoadingOrEmpty(isLoading: Boolean) {
    Text(
        text = if (isLoading) {
            stringResource(R.string.schedule_loading)
        } else {
            stringResource(R.string.db_empty_base)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp)
    )
}

@Composable
private fun SectionOverview(
    sections: List<DbSection>,
    counts: Map<String, Int>,
    onOpenTable: (String) -> Unit,
    onOpenChats: () -> Unit
) {
    // Трекер «уже анимированных» строк: каскад проигрывается один раз за визит экрана, а не при
    // переиспользовании элементов LazyColumn во время скролла (моушен-кит, D1).
    // Значения входа — https://developer.android.com/develop/ui/compose/animation/value-based
    val rowTracker = remember { EnterAnimationTracker() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.db_section_about_title),
                modifier = Modifier
                    // Вход интро-панели — первая ступень каскада (and-fade-up).
                    .enterAnimation(
                        index = 0,
                        tracker = rowTracker,
                        itemKey = "db-intro"
                    )
            ) {
                SettingsHelperText(stringResource(R.string.db_intro))
                SettingsClickRow(
                    title = stringResource(R.string.schedule_open_chats),
                    leadingIcon = Icons.AutoMirrored.Filled.List,
                    onClick = onOpenChats
                )
            }
        }
        // Сквозной индекс каскада по всем секциям: строки входят друг за другом, а не все сразу.
        var rowIndex = 0
        sections.forEach { section ->
            item(key = "section-${section.title}") {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)
                )
            }
            val sectionBase = rowIndex
            rowIndex += section.tables.size
            itemsIndexed(
                items = section.tables,
                key = { _, ref -> "table-${ref.table}" }
            ) { indexInSection, ref ->
                val count = counts[ref.table]
                TableCard(
                    label = ref.label,
                    table = ref.table,
                    isBig = ref.isBig,
                    countText = count?.let(DatabaseViewerSpec::totalCountText) ?: "…",
                    onClick = { onOpenTable(ref.table) },
                    modifier = Modifier.enterAnimation(
                        index = sectionBase + indexInSection + 1,
                        tracker = rowTracker,
                        itemKey = ref.table
                    )
                )
            }
        }
    }
}

/** One table as a rounded card: human label, the raw table name and its row count. */
@Composable
private fun TableCard(
    label: String,
    table: String,
    isBig: Boolean,
    countText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = table + if (isBig) " · ${stringResource(R.string.db_big_table)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TableRows(
    state: DatabaseViewerUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        val countText = DatabaseViewerSpec.totalCountText(state.totalCount)
        val pageText = if (state.pageCount > 0) {
            stringResource(R.string.db_page_of, state.page + 1, state.pageCount)
        } else {
            null
        }
        val readOnlyText = stringResource(R.string.db_read_only)
        Text(
            text = buildString {
                append(countText)
                pageText?.let { append(" · "); append(it) }
                append(" · ")
                append(readOnlyText)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items = state.rows) { index, row ->
                RowCard(
                    index = state.page * DatabaseViewerSpec.PAGE_SIZE + index + 1,
                    row = row
                )
            }
        }

        if (state.pageCount > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = onPrevious,
                    enabled = state.page > 0
                ) { Text(stringResource(R.string.db_prev)) }
                Text(
                    text = "${state.page + 1} / ${state.pageCount}",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = onNext,
                    enabled = state.page + 1 < state.pageCount
                ) { Text(stringResource(R.string.db_next)) }
            }
        }
    }
}

@Composable
private fun RowCard(index: Int, row: DbRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "#$index",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            row.cells.forEach { cell ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = cell.column,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = cell.value ?: "—",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
