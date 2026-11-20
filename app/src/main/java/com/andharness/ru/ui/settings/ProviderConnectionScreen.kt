package com.andharness.ru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.PrimaryGradientButton
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar

/**
 * «Провайдеры» — отдельный экран выбора бэкенда модели: здесь можно подключить любого
 * OpenAI-совместимого провайдера (адрес эндпоинта + API-ключ + имя модели).
 *
 * Экран вынесен из «Настроек» и стоит в нижней навигации; в самих «Настройках» остальные блоки.
 * Он переиспользует [SettingsViewModel] через `hiltViewModel()`:
 * отдельной ViewModel и разнесения состояния нет — оба экрана сохраняют общее персистентное
 * состояние одним и тем же `save()`.
 *
 * Секция `SettingsModelSection` лежит в этом же пакете и вызывается напрямую (без дублирования
 * разметки). Контент приведён к единому стилю внутренних
 * экранов (партия 4): вводный текст — [SettingsSection] «Что это» с [SettingsHelperText], внешние
 * отступы 16 dp; все фиксированные строки локализованы (`stringResource`).
 *
 * References (official docs):
 * - Compose Navigation (`composable` / `navigate`) —
 *   https://developer.android.com/develop/ui/compose/navigation
 * - Material 3 `Scaffold` / `Surface` (rounded section card) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - String resources in Compose (`stringResource`) —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 * - Insets (`consumeWindowInsets` / `imePadding`) —
 *   https://developer.android.com/develop/ui/compose/layouts/insets
 * - `collectAsStateWithLifecycle` / `hiltViewModel` —
 *   https://developer.android.com/topic/libraries/architecture/compose
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderConnectionScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()

    // Трекер входной анимации: блоки входят каскадом (`and-fade-up`) один раз за визит экрана,
    // топ-бар не анимируется — так же, как на экране «Настройки».
    val providerTracker = remember { EnterAnimationTracker() }

    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.provider_title),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // `innerPadding` already applied the system insets; tell children they are consumed,
                // otherwise `imePadding()` adds the navigation bar height a second time.
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsSection(
                    title = stringResource(R.string.provider_section_about_title),
                    modifier = Modifier.enterAnimation(
                        index = 0,
                        tracker = providerTracker,
                        itemKey = "provider_intro"
                    )
                ) {
                    SettingsHelperText(stringResource(R.string.provider_intro))
                }

                ProviderListSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = providerTracker,
                )

                SettingsModelSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = providerTracker,
                )

                if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Тот же паттерн CTA, что и в блоке «Системный промпт» настроек: фирменная кнопка
                // с транзиентным «Сохранение…» / «Сохранено» и блокировкой во время сохранения.
                PrimaryGradientButton(
                    text = when {
                        state.isLoading -> stringResource(R.string.settings_save_saving)
                        state.saved -> stringResource(R.string.settings_save_saved)
                        else -> stringResource(R.string.settings_save)
                    },
                    onClick = viewModel::save,
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
}
