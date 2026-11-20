package com.andharness.ru.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.domain.schedule.ScheduledTask
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsClickRow
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Lists the tasks the agent created via `schedule_create`. Each row can be deleted (cancels the
 * WorkManager work and removes the row) or toggled on/off — no confirmation dialogs, per the
 * agreed product decision (dangerous actions run immediately).
 *
 * Each card has a two-part layout:
 * - a **header row** — the task's creation timestamp on the left (date + localized short weekday +
 *   time, e.g. «10.10.2026, Пт, 18:40»), and the enable/disable [Switch] plus the delete
 *   [IconButton] right-aligned on the same row. One-shot reminders (see [ScheduledTask.isOneShot])
 *   have no recurring enable semantics, so they show no switch; tapping such a card opens the chat
 *   it was created from ([onOpenChat]).
 * - a **full-width body** — the task prompt in a small 13 sp type with an 18 sp line height, shown
 *   in FULL (no `maxLines` cap and no ellipsis) so the card grows to fit every wrapped line and the
 *   user can always read the whole task.
 *
 * Content keeps the unified internal-page style matching the Settings/Memory redesign: the explainer
 * is a [SettingsSection] with muted [SettingsHelperText] and an «Открыть чаты» [SettingsClickRow],
 * every task is a rounded [Surface] card, and spacing (16 dp gutters, 8 dp gaps, `shapes.large`
 * surfaces) follows the shared kit. No state/navigation logic changed.
 *
 * References (official docs):
 * - Material 3 components — https://developer.android.com/develop/ui/compose/components
 * - Lists in Compose — https://developer.android.com/develop/ui/compose/lists
 * - Text styling (`fontSize`, `lineHeight`) —
 *   https://developer.android.com/develop/ui/compose/text/style-text and
 *   https://developer.android.com/develop/ui/compose/text/style-paragraph
 * - Text `maxLines` / `overflow` (omitted for full, un-truncated copy) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/text/style/TextOverflow
 * - `Switch` and `IconButton` —
 *   https://developer.android.com/develop/ui/compose/components/switch and
 *   https://developer.android.com/develop/ui/compose/components/icon-button
 * - Current locale for localized weekday names (`Configuration.getLocales`) —
 *   https://developer.android.com/reference/android/content/res/Configuration#getLocales()
 * - `stringResource` for localized UI strings —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit = {},
    onOpenChats: () -> Unit = {},
    viewModel: ScheduledTasksViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Трекер «уже анимированных» строк: каскад проигрывается один раз за визит экрана, а не при
    // переиспользовании элементов LazyColumn во время скролла (моушен-кит, D1).
    // Значения входа — https://developer.android.com/develop/ui/compose/animation/value-based
    val rowTracker = remember { EnterAnimationTracker() }

    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.home_row_schedules),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SettingsSection(
                title = stringResource(R.string.schedule_section_about_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    // Вход интро-панели — первая ступень каскада (and-fade-up).
                    .enterAnimation(index = 0)
            ) {
                SettingsHelperText(stringResource(R.string.schedule_intro))
                SettingsClickRow(
                    title = stringResource(R.string.schedule_open_chats),
                    leadingIcon = Icons.AutoMirrored.Filled.List,
                    onClick = onOpenChats
                )
            }

            if (state.tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = if (state.isLoading) {
                                stringResource(R.string.schedule_loading)
                            } else {
                                stringResource(R.string.schedule_empty)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (!state.isLoading) {
                            FilledTonalButton(
                                onClick = onOpenChats,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.schedule_open_chats))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = state.tasks,
                        key = { _, task -> task.id }
                    ) { index, task ->
                        ScheduledTaskCard(
                            task = task,
                            onToggle = { enabled -> viewModel.setEnabled(task.id, enabled) },
                            onDelete = { viewModel.delete(task.id) },
                            onOpenChat = onOpenChat,
                            modifier = Modifier.enterAnimation(
                                index = index + 1,
                                tracker = rowTracker,
                                itemKey = task.id
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * One scheduled task as a rounded card. The header row carries the creation timestamp on the left
 * and the trailing controls (enable/disable switch, delete glyph) right-aligned on the same row; the
 * task prompt spans the full card width below in a small 13 sp / 18 sp type.
 *
 * A one-shot reminder has no recurring enable semantics, so it shows no switch; instead a tap on the
 * card opens the chat it was created from (§20). Recurring rows keep the switch and a no-op card tap,
 * so their behaviour is unchanged. The delete glyph is an [IconButton], which already provides the
 * 48 dp M3 minimum hit target.
 */
@Composable
private fun ScheduledTaskCard(
    task: ScheduledTask,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenChat: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val deleteLabel = stringResource(R.string.schedule_delete)
    val chatId = task.chatId
    val oneShot = task.isOneShot
    // App locale drives the weekday name; Configuration.getLocales() reflects per-app language.
    val locale = LocalConfiguration.current.locales[0]
    val createdText = remember(task.createdAt, locale) {
        formatTaskCreatedAt(task.createdAt, locale)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (oneShot && chatId != null) {
                        Modifier.clickable { onOpenChat(chatId) }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = createdText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!oneShot) {
                        Switch(
                            checked = task.enabled,
                            onCheckedChange = onToggle
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = deleteLabel,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Full task text, never truncated: the card grows to fit every wrapped line, so the user
            // can always read what the task actually does.
            Text(
                text = task.prompt,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private val CREATED_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)
private val CREATED_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/**
 * Task creation stamp for the card header, e.g. «10.10.2026, Пт, 18:40» (RU) / «10.10.2026, Fri,
 * 18:40» (EN). The numeric date/time parts are locale-independent, while the short weekday name is
 * produced by [java.time.format.TextStyle] in [locale] and title-cased so it renders «Пт» not «пт».
 * Pure and Android-free (only `java.time` + `Locale`), so it can be JVM-tested.
 *
 * `java.time` is available natively from API 26 (this app's minSdk is 31):
 * https://developer.android.com/reference/java/time/Instant
 * https://developer.android.com/reference/java/time/DayOfWeek#getDisplayName(java.time.format.TextStyle,java.util.Locale)
 * https://developer.android.com/reference/java/time/format/DateTimeFormatter
 */
internal fun formatTaskCreatedAt(
    epochMillis: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val date = CREATED_DATE_FORMAT.format(moment)
    val time = CREATED_TIME_FORMAT.format(moment)
    val weekday = moment.dayOfWeek
        .getDisplayName(TextStyle.SHORT, locale)
        .replaceFirstChar { it.titlecase(locale) }
    return "$date, $weekday, $time"
}
