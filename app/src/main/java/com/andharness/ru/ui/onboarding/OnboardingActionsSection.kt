package com.andharness.ru.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.PrimaryGradientButton
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * Onboarding form actions: the extra-download notice and the brand-gradient «Продолжить» CTA
 * (index 4). [onFinished] runs after a successful save.
 *
 * Re-skinned to the unified internal-page style (партия 4): the notice (previously an accent-styled
 * `AccentPanel` from the legacy `AppTokens` kit) is now a [SettingsSection] with a muted
 * [SettingsHelperText] body. It sits directly above the CTA so users are warned, honestly and
 * visibly, that a large (~1 GB) model download follows after onboarding, preferably on Wi-Fi. It is a
 * display-only notice: the onboarding flow/state is untouched.
 *
 * Docs:
 * - String resources in Compose (`stringResource`) —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 * - Material 3 typography roles (`bodySmall`) —
 *   https://m3.material.io/styles/typography/type-scale-tokens
 */
@Composable
internal fun OnboardingActionsSection(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    tracker: EnterAnimationTracker,
    onFinished: () -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.onboarding_download_notice_label),
        modifier = Modifier.enterAnimation(
            index = 4,
            tracker = tracker,
            itemKey = "onb_download_notice"
        )
    ) {
        SettingsHelperText(stringResource(R.string.onboarding_download_notice))
    }

    PrimaryGradientButton(
        text = if (state.isSaving) {
            stringResource(R.string.settings_save_saving)
        } else {
            stringResource(R.string.onboarding_continue)
        },
        onClick = { viewModel.saveAndContinue(onFinished) },
        enabled = state.canContinue,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .enterAnimation(
                index = 4,
                tracker = tracker,
                itemKey = "onb_primary_btn"
            )
    )
}
