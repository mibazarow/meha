package com.andharness.ru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.domain.llm.LlmAuthStyle
import com.andharness.ru.domain.settings.ContextLimitPolicy
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Подключение (LLM)» + «Режим размышлений (reasoning)» sections: endpoint, API key (with the
 * explicit delete-key confirmation), auth scheme, model catalog, model name and a SHORT read-only
 * explanation of the AUTO-derived context (provider maximum from `/models`, else the fixed default —
 * see [ContextLimitPolicy]), then the reasoning-effort choice. Both are rounded [SettingsSection]
 * cards; the reasoning row uses the shared, compact [SettingsSegmentedSelector] (one grid-aligned
 * row, **green** `primaryContainer` selection instead of the default sky-blue `secondaryContainer`
 * chips), and every button label is pinned to a single line (`maxLines = 1` / `softWrap = false`) on
 * the same 16 dp gutter as the text fields so nothing wraps to a second line.
 *
 * Docs: Material 3 segmented buttons (single-choice semantics) —
 * https://developer.android.com/develop/ui/compose/components/segmented-button
 * Text `maxLines` / `softWrap` / `overflow` —
 * https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextStyle
 * Formatted string resources (`%1$d`, `%%`) —
 * https://developer.android.com/guide/topics/resources/string-resource#FormattingAndStyling
 */
@Composable
internal fun SettingsModelSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    val fieldColors = settingsFieldColors()
    // Destructive: clearing the key is only reachable through the explicit control below.
    var confirmClearKey by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(R.string.settings_llm_title),
        modifier = Modifier.enterAnimation(
            index = 0,
            tracker = tracker,
            itemKey = "settings_llm_label"
        )
    ) {
        // Optional label of the profile being edited/created; the chat backend itself is chosen
        // by the saved-providers list above this form.
        OutlinedTextField(
            value = state.providerName,
            onValueChange = viewModel::onProviderNameChange,
            label = { Text(stringResource(R.string.provider_name_label)) },
            placeholder = { Text(stringResource(R.string.provider_name_placeholder)) },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.endpoint,
            onValueChange = viewModel::onEndpointChange,
            label = { Text(stringResource(R.string.settings_llm_endpoint)) },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text(stringResource(R.string.settings_llm_apikey_label)) },
            supportingText = { Text(stringResource(R.string.settings_llm_apikey_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            colors = fieldColors,
            // Emptying the field and pressing «Сохранить» keeps the stored key, so deletion needs an
            // explicit action: the trailing delete control, offered only while a key is present.
            trailingIcon = {
                IconButton(
                    onClick = { confirmClearKey = true },
                    enabled = state.apiKey.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.settings_llm_delete_key)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        // Auth scheme: which carrier the key travels in. Reuses the same dropdown pattern.
        Text(
            text = stringResource(R.string.settings_llm_auth_style),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        val authStyles = listOf(
            LlmAuthStyle.BEARER to stringResource(R.string.settings_llm_auth_bearer),
            LlmAuthStyle.HEADER to stringResource(R.string.settings_llm_auth_header),
            LlmAuthStyle.QUERY to stringResource(R.string.settings_llm_auth_query),
            LlmAuthStyle.NONE to stringResource(R.string.settings_llm_auth_none)
        )
        var authStyleMenuOpen by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { authStyleMenuOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = authStyles.first { it.first == state.authStyle }.second,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(
                expanded = authStyleMenuOpen,
                onDismissRequest = { authStyleMenuOpen = false }
            ) {
                authStyles.forEach { (style, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            authStyleMenuOpen = false
                            viewModel.onAuthStyleChange(style)
                        }
                    )
                }
            }
        }
        if (state.authStyle == LlmAuthStyle.HEADER) {
            OutlinedTextField(
                value = state.authHeaderName,
                onValueChange = viewModel::onAuthHeaderNameChange,
                label = { Text(stringResource(R.string.settings_llm_header_name)) },
                placeholder = { Text("x-api-key") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (state.authStyle == LlmAuthStyle.QUERY) {
            OutlinedTextField(
                value = state.authQueryParam,
                onValueChange = viewModel::onAuthQueryParamChange,
                label = { Text(stringResource(R.string.settings_llm_query_name)) },
                placeholder = { Text("api_key") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::onModelChange,
            label = { Text(stringResource(R.string.settings_llm_model_name)) },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        var modelsMenuOpen by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Button(
                    onClick = { modelsMenuOpen = true },
                    enabled = state.models.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.settings_llm_models_list),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
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
            Button(
                onClick = viewModel::refreshModels,
                enabled = !state.modelsLoading,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (state.modelsLoading) {
                        stringResource(R.string.settings_llm_loading)
                    } else {
                        stringResource(R.string.settings_llm_refresh)
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (state.modelsError != null) {
            Text(
                text = state.modelsError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (!state.modelsFetched && !state.modelsLoading && state.models.isEmpty()) {
            SettingsHelperText(stringResource(R.string.settings_llm_hint_fill))
        } else if (state.modelsFetched && state.models.isEmpty() && !state.modelsLoading) {
            SettingsHelperText(stringResource(R.string.settings_llm_models_unavailable))
        }
        // Honest one-line current value: the context actually in use for the selected model.
        val contextText = state.modelContextTokens?.let {
            stringResource(R.string.settings_tokens_value, it)
        } ?: stringResource(R.string.settings_context_unknown)
        Text(
            text = stringResource(R.string.settings_llm_context_plain, contextText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Optional manual override: providers report the window in different fields and we cannot
        // detect them all, so the user can type the number when they are confident it is wrong.
        val modelMax = state.modelMaxContextTokens
        val contextFieldLabel = stringResource(R.string.settings_llm_max_context_label)
        val contextFieldSupport = if (modelMax != null) {
            stringResource(R.string.settings_llm_max_context_known, modelMax)
        } else {
            stringResource(R.string.settings_llm_max_context_auto)
        }
        OutlinedTextField(
            value = state.manualContextTokens,
            onValueChange = viewModel::onManualContextChange,
            label = { Text(contextFieldLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(contextFieldSupport) },
            modifier = Modifier.fillMaxWidth()
        )
        SettingsHelperText(stringResource(R.string.settings_llm_context_manual_hint))
        // Dynamic note: the provider maximum when `/models` reported one, else the fixed default.
        SettingsHelperText(
            if (state.contextExplanationUsesProviderMax && modelMax != null) {
                stringResource(R.string.settings_llm_context_reported, modelMax, modelMax * 8 / 10)
            } else {
                stringResource(
                    R.string.settings_llm_context_not_reported,
                    ContextLimitPolicy.DEFAULT_CONTEXT_TOKENS,
                    ContextLimitPolicy.DEFAULT_CONTEXT_TOKENS * 8 / 10,
                )
            }
        )
    }

    SettingsSection(
        title = stringResource(R.string.settings_reasoning_title),
        modifier = Modifier.enterAnimation(
            index = 1,
            tracker = tracker,
            itemKey = "settings_reasoning_label"
        )
    ) {
        SettingsSegmentedSelector(
            options = listOf(
                "off" to stringResource(R.string.settings_reasoning_off),
                "low" to stringResource(R.string.settings_reasoning_low),
                "medium" to stringResource(R.string.settings_reasoning_medium),
                "high" to stringResource(R.string.settings_reasoning_high)
            ),
            selected = state.reasoningEffort,
            onSelect = viewModel::onReasoningEffortChange
        )
    }

    if (confirmClearKey) {
        AlertDialog(
            onDismissRequest = { confirmClearKey = false },
            title = { Text(stringResource(R.string.settings_llm_delete_key_dialog_title)) },
            text = { Text(stringResource(R.string.settings_llm_delete_key_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearKey = false
                    viewModel.clearApiKey()
                }) {
                    Text(stringResource(R.string.settings_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearKey = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}
