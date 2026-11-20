package com.andharness.ru.ui.communications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * One person's timeline: a short «О человеке» summary (composed by `PersonBriefComposer`, the same
 * pure composer the `person_brief` tool uses) above the merged, day-grouped history — interactions
 * (calls/SMS/messenger), transcript moments where this person is the speaker, and linked
 * memories/opinions. The `personId` comes from the `person/{personId}` route and is read by
 * [PersonTimelineViewModel] through its `SavedStateHandle`.
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign:
 * the «О человеке» summary is a [SettingsSection] with muted [SettingsHelperText]; each day is a
 * small section header in the same `titleMedium` / SemiBold role the kit uses; and every timeline
 * entry is a rounded [Surface] card (`shapes.large`, `surfaceContainerLow`) instead of a flat row
 * behind a hairline divider. Gaps are 8 dp and gutters 16 dp. The legacy `AccentPanel` /
 * `AppTokens` kit is no longer used here. All fixed UI copy is localized via
 * `stringResource`.
 *
 * Note (follow-up): the day labels ([PersonTimelineComposer]), the per-row labels produced by
 * [timelineKindLabel], [directionLabel] and the `state.message` text built inside the ViewModel are
 * Android-free Russian-only helpers and are not localized here.
 *
 * Honest states: loading; «человек не найден» when the route's id no longer resolves; and an empty
 * timeline message that names the very sources that were checked. Nothing is invented to fill the
 * screen.
 *
 * References (official docs):
 * - Compose Material 3 `Scaffold`/`Surface` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Compose lists — https://developer.android.com/develop/ui/compose/lists
 * - `stringResource` — https://developer.android.com/develop/ui/compose/resources#string-resources
 * - `hiltViewModel()` / `collectAsStateWithLifecycle()` —
 *   https://developer.android.com/training/dependency-injection/hilt-jetpack ,
 *   https://developer.android.com/reference/kotlin/androidx/lifecycle/compose/package-summary
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonTimelineScreen(
    onBack: () -> Unit,
    viewModel: PersonTimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rowTracker = remember { EnterAnimationTracker() }
    val title = state.person?.displayName ?: stringResource(R.string.person_title_fallback)

    Scaffold(
        topBar = {
            InternalTopBar(
                title = title,
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.isLoading -> CenterMessage(stringResource(R.string.person_loading))

                    state.person == null ->
                        CenterMessage(state.message ?: stringResource(R.string.person_not_found))

                    else -> TimelineContent(state = state, rowTracker = rowTracker)
                }
            }

            // Честная строка ошибки: появляется только когда человек загружен, но действие не удалось.
            if (state.person != null) {
                state.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineContent(state: PersonTimelineUiState, rowTracker: EnterAnimationTracker) {
    val sections = state.sections
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.brief?.let { brief ->
            item(key = "brief") {
                SettingsSection(
                    title = stringResource(R.string.person_about_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .enterAnimation(index = 0)
                ) {
                    SettingsHelperText(brief)
                }
            }
        }

        if (sections.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.person_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                )
            }
        } else {
            var flatIndex = 1
            sections.forEach { section ->
                item(key = "day-${section.dayLabel}") {
                    Text(
                        text = section.dayLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                    )
                }
                items(items = section.entries, key = { entry -> entry.id }) { entry ->
                    TimelineRow(
                        entry = entry,
                        modifier = Modifier.enterAnimation(
                            index = flatIndex++,
                            tracker = rowTracker,
                            itemKey = entry.id
                        )
                    )
                }
            }
        }
    }
}

/** One timeline entry as a rounded card: channel badge + time on top, then the honest title/body/meta. */
@Composable
private fun TimelineRow(entry: PersonTimelineEntry, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timelineKindLabel(entry.kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeColor(entry.kind)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = clockLabel(entry.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entry.title?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            entry.body?.takeIf { it.isNotBlank() }?.let { body ->
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
            entry.meta?.let { meta ->
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun badgeColor(kind: PersonTimelineKind) = when (kind) {
    PersonTimelineKind.TRANSCRIPT, PersonTimelineKind.MEMORY ->
        MaterialTheme.colorScheme.secondary

    PersonTimelineKind.OPINION -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

