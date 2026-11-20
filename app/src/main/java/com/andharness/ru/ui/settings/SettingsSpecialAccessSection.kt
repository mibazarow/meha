package com.andharness.ru.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.theme.SpecialAccessButtonFill

/**
 * Navigation row to the «Все разрешения» screen: a full-width tonal button with a leading glyph in
 * a rounded [SettingsSection] card, followed by a short helper line explaining that the permissions
 * can be pre-granted here or requested on demand later.
 */
@Composable
internal fun SettingsSpecialAccessSection(
    tracker: EnterAnimationTracker,
    onOpenPermissions: () -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_special_title),
        modifier = Modifier.enterAnimation(
            index = 9,
            tracker = tracker,
            itemKey = "settings_special"
        )
    ) {
        // Full-width tonal button with a leading glyph (DESIGN `.btn--block`); bordeaux fill with
        // white content. Colours: `ButtonDefaults.filledTonalButtonColors(containerColor, contentColor)`.
        // https://developer.android.com/reference/kotlin/androidx/compose/material3/FilledTonalButton.composable
        // https://developer.android.com/reference/kotlin/androidx/compose/material3/ButtonDefaults#filledTonalButtonColors(androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color)
        FilledTonalButton(
            onClick = onOpenPermissions,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = SpecialAccessButtonFill,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.settings_permissions_btn))
        }

        SettingsHelperText(stringResource(R.string.settings_special_access_hint))
    }
}
