package com.andharness.ru.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * «Навигация» section: a row back to the Home hub (Memory/audit/skills/communications/…), now in a
 * rounded [SettingsSection] card.
 */
@Composable
internal fun SettingsHomeSection(
    tracker: EnterAnimationTracker,
    onOpenHome: () -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_nav_title),
        modifier = Modifier.enterAnimation(
            index = 11,
            tracker = tracker,
            itemKey = "settings_home_label"
        )
    ) {
        SettingsClickRow(
            title = stringResource(R.string.settings_nav_row_title),
            subtitle = stringResource(R.string.settings_nav_row_subtitle),
            leadingIcon = Icons.Filled.Home,
            onClick = onOpenHome
        )
    }
}
