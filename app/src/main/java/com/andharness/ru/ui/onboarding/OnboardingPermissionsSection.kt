package com.andharness.ru.ui.onboarding

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * Onboarding «Разрешения» block: the header, the lazy-permission explanation and the button that
 * opens the shared «Все разрешения» screen (onboarding stays open). Index 3 of the shared [tracker].
 *
 * Re-skinned to the unified internal-page style (партия 4): a [SettingsSection] card with a muted
 * [SettingsHelperText] body and the same action button; the legacy `AppTokens` `labelLarge`/helper
 * pair is gone. Functionality is unchanged (the screen opens, onboarding stays open).
 *
 * Docs: string resources in Compose (`stringResource`) —
 * https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@Composable
internal fun OnboardingPermissionsSection(
    tracker: EnterAnimationTracker,
    onOpenPermissions: () -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.onboarding_permissions_title),
        modifier = Modifier.enterAnimation(
            index = 3,
            tracker = tracker,
            itemKey = "onb_perms"
        )
    ) {
        SettingsHelperText(stringResource(R.string.onboarding_permissions_body))

        // Прямой переход к полному списку разрешений (не «продолжить»: онбординг остаётся
        // открытым и продолжает вести себя как раньше). Экран разрешений общий с настройками.
        TextButton(onClick = onOpenPermissions) {
            Text(stringResource(R.string.onboarding_permissions_open))
        }
    }
}
