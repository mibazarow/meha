package com.andharness.ru.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * Onboarding intro block: the greeting line. Index 0 of the shared [tracker].
 *
 * The former «Важно» card (a second, top-of-screen explanation that permissions are requested only as
 * needed) was removed: it duplicated the permissions block below (`OnboardingPermissionsSection`),
 * which already carries that explanation next to the «Все разрешения» action. The greeting stays.
 *
 * Re-skinned to the unified internal-page style (партия 4): copy is unchanged apart from localization
 * (`stringResource`).
 *
 * Docs:
 * - String resources in Compose (`stringResource`) —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 * - Material 3 typography roles (`bodyMedium`) —
 *   https://m3.material.io/styles/typography/type-scale-tokens
 */
@Composable
internal fun OnboardingIntroSection(tracker: EnterAnimationTracker) {
    Text(
        text = stringResource(R.string.onboarding_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.enterAnimation(
            index = 0,
            tracker = tracker,
            itemKey = "onb_intro"
        )
    )
}
