package com.andharness.ru.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Язык» section: the two-way interface-language selector («Русский» / «English»). The labels are a
 * language's own name (identical in every locale), so only the title/label copy is localized.
 * Rendered as a rounded [SettingsSection] card whose choice row is the shared, compact
 * [SettingsSegmentedSelector] — the two options split the width evenly and the selected one uses the
 * green `primaryContainer` pill, replacing the default sky-blue (`secondaryContainer`) fill.
 *
 * Docs: Material 3 segmented buttons —
 * https://developer.android.com/develop/ui/compose/components/segmented-button
 * Per-app language preferences —
 * https://developer.android.com/guide/topics/resources/app-languages
 */
@Composable
internal fun SettingsLanguageSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    SettingsSection(
        title = stringResource(R.string.settings_language_title),
        modifier = Modifier.enterAnimation(
            index = 3,
            tracker = tracker,
            itemKey = "settings_language_label"
        )
    ) {
        Text(
            text = stringResource(R.string.settings_language_label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        val options = listOf(
            AppLanguage.RUSSIAN to stringResource(R.string.language_russian),
            AppLanguage.ENGLISH to stringResource(R.string.language_english)
        )
        SettingsSegmentedSelector(
            options = options,
            selected = state.language,
            onSelect = viewModel::onLanguageChange
        )
    }
}
