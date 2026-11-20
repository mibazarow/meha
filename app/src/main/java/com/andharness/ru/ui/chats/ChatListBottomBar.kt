package com.andharness.ru.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andharness.ru.R
import com.andharness.ru.ui.theme.AlwaysDarkBarAccent
import com.andharness.ru.ui.theme.AlwaysDarkBarAccentContent
import com.andharness.ru.ui.theme.AlwaysDarkBarInk
import com.andharness.ru.ui.theme.AlwaysDarkSurface
import com.andharness.ru.ui.theme.BrandGold
import com.andharness.ru.ui.theme.OnBrandGold

/**
 * Compact floating bottom panel for the chat list (Telegram-style). Instead of the old full-width
 * [androidx.compose.material3.NavigationBar], this is a fully-rounded pill [Surface] (100% corners,
 * [RoundedCornerShape] with `percent = 50`) inset from the screen edges (8dp sides, 12dp bottom)
 * and lifted above the system navigation bar, so it reads as a floating panel on the app background.
 *
 * It carries all three destinations inside the same bar — «Настройки», «Провайдеры» and «База»
 * (the latter opens the Home hub) as small icon+label columns — plus the accent «Новый чат» pill
 * ([Button]). The separate extended FAB was removed.
 *
 * The panel is ALWAYS dark: it uses the fixed [AlwaysDarkSurface] fill (the dark-theme
 * `surfaceContainer`, `#151B15`) with fixed light content ([AlwaysDarkBarInk] `#A6ADA4` and the
 * bright [AlwaysDarkBarAccent] / [AlwaysDarkBarAccentContent] accent), so it renders pixel-identically in the light and the
 * dark theme regardless of the OS setting.
 *
 * Sizing: the bar is 50dp tall and the accent pill is 40dp, so the pill is centred with an equal
 * ~5dp above and below it — the bar hugs the pill instead of padding it loosely. The three
 * destinations share the remaining width via `weight(1f)`, which keeps them legible next to the
 * pill. The narrow side margins (8dp) buy the weighted items enough width for their FULL labels —
 * on the ~360dp-wide test device the items are otherwise too narrow and «Настройки»/«Провайдеры»
 * ellipsize to «Настро…»/«Провай…».
 *
 * Docs: `Surface` (shape/color/elevation; content clipped to the shape) —
 * https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Surface(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp,androidx.compose.foundation.BorderStroke,kotlin.Function0)
 * `RoundedCornerShape(percent = 50)` (fully-rounded corners) —
 * https://developer.android.com/reference/kotlin/androidx/compose/foundation/shape/RoundedCornerShape
 * `Button` (accent pill) — https://developer.android.com/develop/ui/compose/components/button
 * `rememberVectorPainter` / `painterResource` (the `Painter` icon sources for the bar items) —
 * https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/vector/package-summary#rememberVectorPainter(androidx.compose.ui.graphics.vector.ImageVector)
 * `Modifier.navigationBarsPadding()` —
 * https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/navigationBarsPadding.modifier
 */
@Composable
internal fun ChatListBottomBar(
    onOpenSettings: () -> Unit,
    onOpenProvider: () -> Unit,
    onOpenHome: () -> Unit,
    onNewChat: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Small top gap above the panel; side/bottom margins give the Telegram floating look.
            // Side margins trimmed to 8dp so the three weighted items + the pill all fit their full
            // labels on a narrow (~360dp) screen without ellipsis.
            .padding(horizontal = 8.dp)
            .padding(top = 8.dp)
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
    ) {
        Surface(
            // 50dp bar around a 40dp pill = an equal 5dp above and below the pill (symmetric hug).
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(percent = 50),
            // ALWAYS dark, in both themes: the fixed dark-theme `surfaceContainer` value. The fixed
            // content colour keeps the bar's ink light even in the light theme.
            color = AlwaysDarkSurface,
            contentColor = AlwaysDarkBarInk,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChatListBottomBarItem(
                    icon = rememberVectorPainter(Icons.Filled.Settings),
                    label = stringResource(R.string.settings_title),
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f)
                )
                ChatListBottomBarItem(
                    icon = rememberVectorPainter(Icons.Filled.Build),
                    label = stringResource(R.string.provider_title),
                    onClick = onOpenProvider,
                    modifier = Modifier.weight(1f)
                )
                ChatListBottomBarItem(
                    // «База» → the Home hub (Memory / Scheduled tasks / Automation rules / Skills
                    // / Communications / Suggestions / Audit). The base Material icon set has no
                    // grid/apps glyph, so this is the local `ic_sections` vector.
                    icon = painterResource(R.drawable.ic_sections),
                    label = stringResource(R.string.chat_nav_base),
                    onClick = onOpenHome,
                    modifier = Modifier.weight(1f)
                )
                NewChatPillButton(onClick = onNewChat)
            }
        }
    }
}

/**
 * Accent «Новый чат» action rendered as a fully-rounded pill inside the floating bar: a filled
 * [Button] whose accent follows the app theme (gold [BrandGold]/[OnBrandGold] on the light surface,
 * bright [AlwaysDarkBarAccent]/[AlwaysDarkBarAccentContent] on the dark surface) holding a leading `Icons.Filled.Add` glyph
 * (~18dp) followed by the «Новый чат» label. 40dp
 * tall so it sits centred (5dp above/below) in the 50dp bar; the stadium shape matches the bar's own
 * [RoundedCornerShape] `percent = 50` treatment, and the horizontal content padding is set to 16dp so
 * the wider icon+label pill still leaves the weighted destinations their full width (the nav-item
 * padding is trimmed to 1dp to compensate). The label is 14sp — a touch larger than the old 12sp, so
 * it reads clearly while still fitting the ~360dp screen. The pill keeps the «Новый чат» accessible
 * name (an explicit `contentDescription`) so TalkBack announces the same action as before.
 *
 * Docs:
 * - `Button` (`shape` / `colors` / `contentPadding`, contents are a `RowScope`) —
 *   https://developer.android.com/develop/ui/compose/components/button
 * - `Icons.Filled.Add` (the leading «＋» glyph) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material/icons/filled/package-summary#Add()
 * - `Icon(imageVector = …)` (glyph hosted in the button; tint defaults to the button's
 *   `LocalContentColor`, i.e. `onPrimary`) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Icon(androidx.compose.ui.graphics.vector.ImageVector,kotlin.String,androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Color)
 * - `TextStyle.copy` / `TextStyle.fontSize` (setting the `labelLarge` to 14sp) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextStyle
 * - `Modifier.semantics { contentDescription = … }` (exposes a text description for accessibility
 *   services such as TalkBack) —
 *   https://developer.android.com/develop/ui/compose/accessibility/semantics
 */
@Composable
private fun NewChatPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = stringResource(R.string.chat_new_chat)
    // Theme-aware accent: the bar itself stays black, but the pill reads gold on the LIGHT app
    // surface and keeps the bright green on the DARK surface. `Color.luminance()` is the app-theme
    // signal (see the zebra stripe above), so it stays correct even when the app theme differs from
    // the OS setting.
    // https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Button(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .semantics { contentDescription = label }
            .testTag("new_chat_button"),
        shape = RoundedCornerShape(percent = 50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) AlwaysDarkBarAccent else BrandGold,
            contentColor = if (dark) AlwaysDarkBarAccentContent else OnBrandGold
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            maxLines = 1
        )
    }
}

/**
 * One item of the floating bottom bar: a small icon (20dp, given as a [Painter] so it can be either
 * a core-set vector via [rememberVectorPainter] or a local drawable via `painterResource`) above a
 * tiny label (`labelSmall` trimmed to 10sp) with tight spacing, fixed light `AlwaysDarkBarInk`
 * ink (the bar is always dark, so the ink must stay light in every theme) and a
 * rounded click target. The whole column is a single accessible, clickable target that shares the
 * row's free width with its siblings. The content padding is trimmed to 1dp per side (from 8dp) so
 * the full «Настройки»/«Провайдеры» words fit on a ~360dp-wide screen next to the wider icon+label
 * «Новый чат» pill instead of ellipsizing.
 *
 * Docs: `TextStyle.copy` / `TextStyle.fontSize` (trimming the 11sp `labelSmall` to 10sp) —
 * https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextStyle
 */
@Composable
private fun ChatListBottomBarItem(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 1.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = AlwaysDarkBarInk,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = AlwaysDarkBarInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
