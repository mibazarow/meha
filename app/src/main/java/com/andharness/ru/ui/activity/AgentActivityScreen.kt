package com.andharness.ru.ui.activity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.ui.components.CenterMessage
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * «Что агент сделал» — the human-readable activity report built from the tool-call audit log, with
 * an «Отменить» action for every row that is still reversible through the M3 undo journal.
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign:
 * the explainer is a [SettingsSection] with muted [SettingsHelperText], day headers use the theme
 * `titleSmall`/SemiBold role, and every action is a rounded [Surface] card (`shapes.large`,
 * `surfaceContainerLow`) instead of a flat row followed by a divider. Spacing (16 dp gutters, 8 dp
 * gaps) and `bodyLarge` / `bodySmall` typography follow the shared kit. The screen only renders data
 * that is already persisted — no DB access beyond `ToolAuditRepository.recent(limit)` and the
 * in-memory `UndoStore`. All fixed UI copy is localized.
 *
 * Note (follow-up): [AgentActivityItem.title], [AgentActivityMapper.timeOfDay] and the day group
 * labels come from the activity mapper (domain) and are still Russian-only.
 *
 * References (official docs):
 * - Material 3 `Scaffold` / `TopAppBar` / `TextButton` / `Surface` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - `LazyColumn` + `stickyHeader` (still `ExperimentalFoundationApi` in Compose 1.7) —
 *   https://developer.android.com/develop/ui/compose/lists
 * - Window insets / `consumeWindowInsets` —
 *   https://developer.android.com/develop/ui/compose/layouts/insets
 * - `stringResource` — https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AgentActivityScreen(
    onBack: () -> Unit,
    onOpenChats: () -> Unit = {},
    viewModel: AgentActivityViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rowTracker = remember { EnterAnimationTracker() }

    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.home_row_activity),
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.activity_refresh_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // `innerPadding` already applied the system insets; tell children they are consumed
                // so a nested inset-aware modifier does not add them a second time.
                // https://developer.android.com/develop/ui/compose/layouts/insets
                .consumeWindowInsets(innerPadding)
        ) {
            SettingsSection(
                title = stringResource(R.string.activity_section_about_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .enterAnimation(index = 0)
            ) {
                SettingsHelperText(stringResource(R.string.activity_intro))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val groups = state.groups
                when {
                    state.isLoading && groups.isEmpty() ->
                        CenterMessage(stringResource(R.string.schedule_loading))

                    groups.isEmpty() -> EmptyState(onOpenChats = onOpenChats)

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groups.forEach { group ->
                            // Sticky day header stays visible while its day scrolls.
                            stickyHeader(key = "day_${group.label}") {
                                DayHeader(label = group.label)
                            }
                            items(
                                items = group.items,
                                key = { item -> item.auditId }
                            ) { item ->
                                ActivityCard(
                                    item = item,
                                    isUndoing = state.undoingAuditId == item.auditId,
                                    onUndo = { viewModel.undo(item) },
                                    modifier = Modifier.enterAnimation(
                                        index = 1,
                                        tracker = rowTracker,
                                        itemKey = item.auditId
                                    )
                                )
                            }
                        }
                    }
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}


/** Empty state: a clear "nothing yet" line, the explainer and a shortcut into the chat. */
@Composable
private fun BoxScope.EmptyState(onOpenChats: () -> Unit) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.activity_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.activity_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onOpenChats) {
            Text(stringResource(R.string.activity_start_chat))
        }
    }
}

/**
 * Sticky day group header. It paints the theme `surface` behind the label so list rows scrolling
 * underneath do not show through the sticky bar.
 */
@Composable
private fun DayHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 10.dp, bottom = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * One report row as a rounded card: the human tool [AgentActivityItem.title] and time, the result
 * («Успешно»/«Ошибка») in the `primary`/`error` role, the already-redacted argument preview and —
 * only when the action is still reversible — an «Отменить» action. The button is disabled while its
 * own undo is running.
 */
@Composable
private fun ActivityCard(
    item: AgentActivityItem,
    isUndoing: Boolean,
    onUndo: () -> Unit,
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
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${AgentActivityMapper.timeOfDay(item.at)} · " +
                        (if (item.success) {
                            stringResource(R.string.audit_success)
                        } else {
                            stringResource(R.string.audit_error)
                        }),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                if (item.argsPreview.isNotBlank()) {
                    Text(
                        text = item.argsPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (item.isUndoable) {
                TextButton(onClick = onUndo, enabled = !isUndoing) {
                    Text(
                        if (isUndoing) {
                            stringResource(R.string.activity_undoing)
                        } else {
                            stringResource(R.string.activity_undo)
                        }
                    )
                }
            }
        }
    }
}
