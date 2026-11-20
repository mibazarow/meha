package com.andharness.ru.ui.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.usage.AutomationSuggestion
import com.andharness.ru.ui.components.CenterMessage
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.PrimaryGradientButton
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.permission.SpecialAccessIntents
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection
import kotlin.math.roundToInt

/**
 * «Подсказки» — predictive automation suggestions (update.md idea #16).
 *
 * Content is re-skinned to the unified internal-page style (matching the Settings/Memory redesign):
 * the explainer is a [SettingsSection] with muted [SettingsHelperText], and each suggestion is a
 * rounded [Surface] card (`shapes.large`, `surfaceContainerLow`) instead of a flat [androidx.compose.material3.Card]
 * separated by dividers. Spacing (16 dp gutters, 8 dp gaps) and `bodyLarge` / `bodySmall` typography
 * follow the shared kit. All UI strings are localized with [stringResource]; behaviour is unchanged.
 *
 * Each card shows what was detected, how confident the analyzer is, and the rule it would create;
 * «Создать правило» turns it into a real automation (trigger `time` + action `notify`) through the
 * shared suggestion engine, «Скрыть» hides it for good. The empty and no-usage-access states are
 * explicit rather than silently blank.
 *
 * Note (follow-up): [AutomationSuggestion.title] / [AutomationSuggestion.description] and
 * `triggerConfig` are produced by the usage analyzer (domain), so their text is still Russian-only.
 *
 * References (official docs):
 * - Material 3 `Surface` (rounded card surface; content is clipped to the shape) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Surface(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp,androidx.compose.foundation.BorderStroke,kotlin.Function0)
 * - Compose lists — https://developer.android.com/develop/ui/compose/lists
 * - `stringResource` for localized UI strings —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(
    onBack: () -> Unit,
    viewModel: SuggestionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Трекер «уже анимированных» карточек: каскад проигрывается один раз за визит экрана, а не при
    // переиспользовании элементов LazyColumn во время скролла (моушен-кит, D1).
    // Значения входа — https://developer.android.com/develop/ui/compose/animation/value-based
    val cardTracker = remember { EnterAnimationTracker() }

    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.home_row_suggestions),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsSection(
                    title = stringResource(R.string.suggestions_section_about_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        // Вход интро-панели — первая ступень каскада (and-fade-up).
                        .enterAnimation(index = 0)
                ) {
                    SettingsHelperText(stringResource(R.string.suggestions_intro))
                }

                when {
                    state.isLoading -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) { CenterMessage(stringResource(R.string.schedule_loading), textAlign = null) }

                    !state.usageAccessGranted -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        UsageAccessRequiredState()
                    }

                    state.suggestions.isEmpty() -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        CenterMessage(stringResource(R.string.suggestions_empty), textAlign = null)
                    }

                    else -> LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = state.suggestions,
                            key = { _, suggestion -> suggestion.id }
                        ) { index, suggestion ->
                            SuggestionCard(
                                suggestion = suggestion,
                                isCreating = state.creatingId == suggestion.id,
                                onAccept = { viewModel.accept(suggestion) },
                                onDismiss = { viewModel.dismiss(suggestion) },
                                // Каскадный вход карточки — ступени после интро-панели.
                                modifier = Modifier.enterAnimation(
                                    index = index + 1,
                                    tracker = cardTracker,
                                    itemKey = suggestion.id
                                )
                            )
                        }
                        state.error?.let { error ->
                            item {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * Пустое состояние «нет доступа к данным об использовании»: текст-подсказка плюс кнопка, которая
 * открывает системный экран выдачи доступа. Интент строит общий [SpecialAccessIntents] (тот же,
 * что использует чат), запуск обёрнут в `runCatching`, чтобы отсутствие экрана на устройстве не
 * роняло приложение. Ссылка на действие:
 * https://developer.android.com/reference/android/provider/Settings#ACTION_USAGE_ACCESS_SETTINGS
 */
@Composable
private fun BoxScope.UsageAccessRequiredState() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.suggestions_usage_access),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PrimaryGradientButton(
            text = stringResource(R.string.suggestions_open_settings),
            onClick = {
                runCatching {
                    SpecialAccessIntents
                        .intentFor(context, SpecialAccess.USAGE_ACCESS)
                        ?.let(context::startActivity)
                }
            }
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: AutomationSuggestion,
    isCreating: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = suggestion.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.suggestions_confidence,
                    (suggestion.confidence * 100).roundToInt(),
                    suggestion.days,
                    suggestion.observedDays,
                    suggestion.triggerConfig
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !isCreating) {
                    Text(stringResource(R.string.suggestions_dismiss))
                }
                PrimaryGradientButton(
                    text = if (isCreating) {
                        stringResource(R.string.suggestions_creating)
                    } else {
                        stringResource(R.string.suggestions_create_rule)
                    },
                    onClick = onAccept,
                    enabled = !isCreating
                )
            }
        }
    }
}
