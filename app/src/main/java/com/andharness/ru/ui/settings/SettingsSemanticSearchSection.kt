package com.andharness.ru.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Поиск» section: the semantic-search (embeddings) toggle, now a rounded [SettingsSection] card
 * with a whole-row switch. The setting, its default and the «Сохранить» commit are unchanged.
 *
 * Docs: Material 3 `Switch` — https://developer.android.com/develop/ui/compose/components/switch
 */
@Composable
internal fun SettingsSemanticSearchSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    SettingsSection(
        title = stringResource(R.string.settings_search_title),
        modifier = Modifier.enterAnimation(
            index = 4,
            tracker = tracker,
            itemKey = "settings_search_label"
        )
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_semantic_label),
            subtitle = stringResource(R.string.settings_semantic_hint),
            checked = state.semanticSearchEnabled,
            onCheckedChange = viewModel::onSemanticSearchEnabledChange
        )
    }
}
