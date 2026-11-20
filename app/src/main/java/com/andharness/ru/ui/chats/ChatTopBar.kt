package com.andharness.ru.ui.chats

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andharness.ru.R
import com.andharness.ru.ui.theme.AlwaysDarkSurface
import com.andharness.ru.ui.theme.AlwaysDarkSurfaceInk

/**
 * The chat top bar: a plain back arrow («крылышко») on the left, the current chat name in a rounded
 * pill next to it, and a single search action on the right. A search action opens the in-chat
 * search bar (redesign §4 «Экран чата», phase 9); chat settings, the theme control (phase 1) and
 * the hands-free voice toggle (phase 5) all remain removed.
 *
 * Source citations for the Compose controls used:
 * - `TopAppBar` with `title`, `navigationIcon` and `actions` (the search `IconButton`), and the
 *   fact that app bars are hosted by `Scaffold`, which owns the status-bar insets:
 *   https://developer.android.com/develop/ui/compose/components/app-bars
 * - Status-bar / system insets in Compose (the app bar applies them; the Scaffold `innerPadding`
 *   is consumed by the content below):
 *   https://developer.android.com/develop/ui/compose/layouts/insets
 * - `IconButton` (48 dp accessible touch target wrapping the back arrow and the search action):
 *   https://developer.android.com/develop/ui/compose/components/icon-button
 * - `Surface` (the chat-name pill; `shape` + explicit `color`/`contentColor`), `RoundedCornerShape`
  *   (`percent = 50` gives the pill). The pill is ALWAYS dark — it uses the fixed theme tokens
  *   [AlwaysDarkSurface] / [AlwaysDarkSurfaceInk] (the same dark fill/ink as the chat-list bottom
  *   bar) instead of the
 *   theme `surfaceVariant`/`onSurfaceVariant`, so it renders identically in light and dark:
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 *   https://developer.android.com/develop/ui/compose/graphics/draw/shapes
 *   https://developer.android.com/develop/ui/compose/designsystems/material3#color-scheme
 * - `androidx.compose.ui.graphics.Color` (fixed literal colours that ignore the active theme):
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Color
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    title: String,
    onBack: () -> Unit,
    onSearchClick: () -> Unit
) {
    TopAppBar(
        title = {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = AlwaysDarkSurface,
                contentColor = AlwaysDarkSurfaceInk
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back_cd),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            // Toggles the in-chat search bar (redesign §4 «Экран чата», phase 9).
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.chat_topbar_search_cd),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}
