package com.andharness.ru.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.andharness.ru.R

/**
 * The single top-bar ("chrome") shared by every internal, non-chat screen.
 *
 * It replaces the previous per-screen copy-paste of a `CenterAlignedTopAppBar` plus a hand-rolled
 * circular back button (`Box(size(50.dp), CircleShape, surfaceVariant)`) that was duplicated across
 * ~13 screens. The style matches the redesigned chat chrome: a plain Material 3 back arrow on the
 * left, the screen title left-aligned in the theme's own typography, a flat/transparent container
 * and no elevation. Only the title and the back action are fixed; a screen that needs one extra
 * action passes it through the optional [actions] slot.
 *
 * The bar is meant to be hosted by a `Scaffold` `topBar` slot, exactly like the app bars it
 * replaces, so the visible layout of each screen is unchanged.
 *
 * @param title the screen title, shown left-aligned; single line, ellipsized when long.
 * @param onBack invoked by the leading back arrow (same behaviour as each screen's old back button).
 * @param modifier optional modifier for the bar.
 * @param actions optional trailing actions (e.g. a refresh button); empty by default.
 *
 * Sources (official documentation):
 * - Material 3 top app bars: `title`, `navigationIcon` and `actions`, and the fact that app bars are
 *   generally passed to `Scaffold`, which has specific parameters to receive them —
 *   https://developer.android.com/develop/ui/compose/components/app-bars
 * - `TopAppBar` composable signature (small, left-aligned top app bar) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/TopAppBar.composable
 * - `TopAppBarDefaults.topAppBarColors(containerColor, scrolledContainerColor,
 *   navigationIconContentColor, titleContentColor, actionIconContentColor)` (flat/transparent
 *   container colours, no elevation) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/TopAppBarDefaults
 * - Window insets: `TopAppBar` applies the status-bar insets by default and the `Scaffold`
 *   `innerPadding` is then consumed by the content below —
 *   https://developer.android.com/develop/ui/compose/layouts/insets
 * - `IconButton` (48 dp accessible touch target wrapping the back arrow) —
 *   https://developer.android.com/develop/ui/compose/components/icon-button
 * - `Icons.AutoMirrored.Filled.ArrowBack` (RTL-aware back arrow) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material/icons/automirrored/filled/ArrowBack
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternalTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back_cd),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}
