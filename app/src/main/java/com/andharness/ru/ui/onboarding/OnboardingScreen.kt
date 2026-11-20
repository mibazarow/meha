package com.andharness.ru.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * Onboarding step 1: LLM connection (cloud API) followed by the permissions note.
 *
 * Unified internal-page style (партия 4): each onbording block is a rounded `SettingsSection` card
 * (`ui/settings/SettingsSection`) with theme typography and muted helpers — the legacy `AccentPanel`
 * / `AppTokens` kit is no longer used here; the primary CTA stays the brand-gradient
 * `PrimaryGradientButton`. All state/decisions still live in [OnboardingViewModel]; the copy is
 * unchanged apart from localization (`stringResource`). The brand header and the cascade animation
 * (`EnterAnimationTracker`) are deliberately kept.
 *
 * The body is split into section composables (intro, model, permissions, actions); this entry point
 * owns the pinned header, the scroll container and the finished-navigation side effect.
 *
 * References (official docs):
 * - Material 3 components — https://developer.android.com/develop/ui/compose/components
 * - Lists in Compose — https://developer.android.com/develop/ui/compose/lists
 * - `Scaffold` container/content color and `WindowInsets` (status-bar inset for the header row) —
 *   https://developer.android.com/develop/ui/compose/system/material-insets
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Трекер входной анимации: блоки онбординга входят каскадом (`and-fade-up`) один раз за визит
    // экрана. Строка заголовка (название + переключатель темы) — единый анимируемый блок
    // (DESIGN/01-onboarding.html).
    val onbTracker = remember { EnterAnimationTracker() }

    LaunchedEffect(state.isConfigured) {
        if (state.isConfigured) onFinished()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // `innerPadding` уже применил системные inset'ы (status/navigation bars). Сообщаем
                // дочерним модификаторам, что эта часть inset'ов израсходована, иначе `imePadding()`
                // добавит высоту navigation bar второй раз, когда откроется клавиатура.
                // https://developer.android.com/develop/ui/compose/layouts/insets
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            // D8/D3: без app bar на онбординге общий переключатель темы (D3) стоит в конце строки
            // заголовка: название бренда занимает остаток ширины (`weight(1f)`), оба элемента
            // выровнены по центру по вертикали. Один блок вместо двух экономит вертикальное место.
            // Документация: Row / `Modifier.weight` —
            // https://developer.android.com/develop/ui/compose/layouts/basics
            // Заголовок закреплён: он лежит вне прокручиваемого контейнера ниже, поэтому не
            // уезжает при скролле.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
                    .enterAnimation(
                        index = 0,
                        tracker = onbTracker,
                        itemKey = "onb_brand"
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            // Остальной онбординг прокручивается: `weight(1f)` сжимает область при появлении
            // клавиатуры, `verticalScroll` оставляет весь контент доступным.
            // https://developer.android.com/develop/ui/compose/modifiers
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OnboardingIntroSection(tracker = onbTracker)
                OnboardingModelSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = onbTracker,
                )
                OnboardingPermissionsSection(
                    tracker = onbTracker,
                    onOpenPermissions = onOpenPermissions,
                )
                OnboardingActionsSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = onbTracker,
                    onFinished = onFinished,
                )
            }
        }
    }
}
