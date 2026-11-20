package com.andharness.ru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.domain.style.AddressForm
import com.andharness.ru.domain.style.StyleMode
import com.andharness.ru.domain.style.StyleTraitKey
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.theme.BrandGold
import com.andharness.ru.ui.theme.OnBrandGold
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Стиль общения»: the user-visible face of the learned style profile. Shows the current mode,
 * what has been learned, a manual card editor, the strong-language switch and a forget action.
 * Everything is a passive view of `StyleProfileStore`; the assistant never announces adaptation.
 *
 * Docs: Material 3 `FilterChip` —
 * https://developer.android.com/develop/ui/compose/components/chip#filter-chip
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsStyleSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    tracker: EnterAnimationTracker,
) {
    SettingsSection(
        title = stringResource(R.string.settings_style_title),
        modifier = Modifier.enterAnimation(
            index = 5,
            tracker = tracker,
            itemKey = "settings_style"
        )
    ) {
        SettingsHelperText(stringResource(R.string.settings_style_hint))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StyleMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.styleMode == mode,
                    onClick = { viewModel.onStyleModeChange(mode) },
                    label = { Text(stringResource(modeLabel(mode))) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandGold,
                        selectedLabelColor = OnBrandGold,
                    ),
                )
            }
        }

        if (state.styleMode == StyleMode.MANUAL) {
            OutlinedTextField(
                value = state.styleManualCard,
                onValueChange = viewModel::onStyleManualCardChange,
                label = { Text(stringResource(R.string.settings_style_manual_label)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                supportingText = { Text("${state.styleManualCard.length}/200") },
            )
            TextButton(
                onClick = viewModel::onStyleManualCardSave,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.settings_style_manual_save))
            }
        }

        SettingsSwitchRow(
            title = stringResource(R.string.settings_style_profanity_title),
            subtitle = stringResource(R.string.settings_style_profanity_hint),
            checked = state.styleProfanityEnabled,
            onCheckedChange = viewModel::onStyleProfanityChange,
        )

        val learned = learnedSummary(state)
        if (learned.isNotEmpty()) {
            SettingsHelperText(stringResource(R.string.settings_style_learned, learned))
        } else {
            SettingsHelperText(stringResource(R.string.settings_style_no_data))
        }
        state.styleCard?.takeIf { it.isNotBlank() }?.let { card ->
            SettingsHelperText(stringResource(R.string.settings_style_tone, card))
        }
        if (state.styleSamples > 0) {
            SettingsHelperText(stringResource(R.string.settings_style_samples, state.styleSamples))
        }
        TextButton(
            onClick = viewModel::onStyleForget,
            enabled = state.styleSamples > 0 || state.styleCard != null,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.settings_style_forget))
        }
    }
}

private fun modeLabel(mode: StyleMode): Int = when (mode) {
    StyleMode.AUTO -> R.string.settings_style_mode_auto
    StyleMode.NEUTRAL -> R.string.settings_style_mode_neutral
    StyleMode.MANUAL -> R.string.settings_style_mode_manual
}

/** Localized comma-separated summary of the active traits, in priority order. */
@Composable
private fun learnedSummary(state: SettingsUiState): String {
    val parts = buildList {
        when (state.styleAddressForm) {
            AddressForm.TY -> add(stringResource(R.string.settings_style_address_ty))
            AddressForm.VY -> add(stringResource(R.string.settings_style_address_vy))
            null -> Unit
        }
        state.styleTraits.forEach { trait ->
            val label = when (trait) {
                StyleTraitKey.ADDRESS -> null
                StyleTraitKey.BREVITY -> R.string.settings_style_brevity
                StyleTraitKey.SLANG -> R.string.settings_style_slang
                StyleTraitKey.PROFANITY -> R.string.settings_style_profanity
                StyleTraitKey.EMOJI -> R.string.settings_style_emoji
                StyleTraitKey.PUNCTUATION -> R.string.settings_style_punctuation
                StyleTraitKey.DIRECTNESS -> R.string.settings_style_directness
                StyleTraitKey.HUMOR -> R.string.settings_style_humor
            }
            if (label != null) add(stringResource(label))
        }
    }
    return parts.joinToString(", ")
}
