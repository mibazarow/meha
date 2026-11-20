package com.andharness.ru.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection
import com.andharness.ru.ui.settings.settingsFieldColors

/**
 * Onboarding «Модель» step: the inline cloud fields (endpoint, API key, model name, catalog picker)
 * plus the error line. Onboarding is cloud-provider-only.
 *
 * Re-skinned to the unified internal-page style (партия 4): the fields now live inside one
 * [SettingsSection] card ([settingsFieldColors] on the raised surface, theme typography), matching
 * [com.andharness.ru.ui.settings.SettingsModelSection], instead of loose fields under a legacy
 * `labelLarge` header. The per-field cascade animation is gone — the single card animates (index 2 of
 * the shared [tracker]); functionality and copy are unchanged apart from localization.
 *
 * Docs:
 * - Material 3 text fields / dropdown menu —
 *   https://developer.android.com/develop/ui/compose/components/text-field ,
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - String resources in Compose (`stringResource`) —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@Composable
internal fun OnboardingModelSection(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    tracker: EnterAnimationTracker,
) {
    val fieldColors = settingsFieldColors()
    var modelsMenuOpen by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(R.string.onboarding_model_title),
        modifier = Modifier.enterAnimation(index = 2, tracker = tracker, itemKey = "onb_model")
    ) {
        OutlinedTextField(
            value = state.endpoint,
            onValueChange = viewModel::onEndpointChange,
            label = { Text(stringResource(R.string.settings_llm_endpoint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text(stringResource(R.string.settings_llm_apikey_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::onModelChange,
            label = { Text(stringResource(R.string.settings_llm_model_name)) },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        // Выпадающий список моделей и «Обновить список» — зеркало облачного блока «Провайдеры»:
        // каталог из `GET /models` грузится автоматически после ввода адреса и ключа (с дебаунсом)
        // либо по кнопке. Поле выше остаётся editable-фолбэком, если провайдер список не отдаёт.
        // Material 3 `DropdownMenu` / `DropdownMenuItem` —
        // https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                OutlinedButton(
                    onClick = { modelsMenuOpen = true },
                    enabled = state.models.isNotEmpty()
                ) {
                    Text(stringResource(R.string.settings_llm_models_list))
                }
                DropdownMenu(
                    expanded = modelsMenuOpen,
                    onDismissRequest = { modelsMenuOpen = false }
                ) {
                    state.models.forEach { id ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = id,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                modelsMenuOpen = false
                                viewModel.onSelectModel(id)
                            }
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = viewModel::refreshOnboardingModels,
                enabled = !state.modelsLoading
            ) {
                Text(
                    if (state.modelsLoading) {
                        stringResource(R.string.settings_llm_loading)
                    } else {
                        stringResource(R.string.settings_llm_refresh)
                    }
                )
            }
        }

        when {
            state.modelsError != null -> Text(
                text = state.modelsError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )

            !state.modelsFetched && !state.modelsLoading && state.models.isEmpty() ->
                SettingsHelperText(stringResource(R.string.onboarding_models_hint_fill))

            state.modelsFetched && state.models.isEmpty() && !state.modelsLoading ->
                SettingsHelperText(stringResource(R.string.settings_llm_models_unavailable))
        }

        if (state.error != null) {
            Text(
                text = state.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Информационная сноска (текст, без изменения потока): приложение умеет работать полностью
        // офлайн на модели, встроенной в устройство. Настройка живёт в «Настройках»; здесь ничего не
        // форсируется и онбординг не меняется.
        SettingsHelperText(stringResource(R.string.onboarding_local_note))
    }
}
