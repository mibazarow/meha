package com.andharness.ru.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

/**
 * Chat list — the app's launch root and the only screen that carries the bottom navigation panel
 * (redesign §4 «Список чатов» / phase 7). The chrome mirrors Telegram (inspired, not copied):
 *
 * - there is no title/header any more: the screen's topmost element is a fully-rounded, editable
 *   search field pinned right under the system status bar (hint «Поиск по чатам», leading magnifier,
 *   trailing clear ✕). Typing runs a debounced, cancellable semantic + lexical search over the
 *   app's own chats and swaps the list for snippet results (phase 9);
 * - rows are Telegram-like: the chat title (bold) and a one-line preview of the last message on the
 *   left, and a trailing column with the relative time on top
 *   and the always-visible row actions below it — a small pin toggle (filled/accent when pinned,
 *   outlined/muted otherwise) next to a trash button that opens a delete-confirmation dialog. A
 *   plain tap anywhere else on the row opens the chat; the old long-press Pin/Delete menu is gone;
 * - rows alternate a clear "zebra" background for separation instead of dividers: 1-based even rows
 *   (list index 1, 3, …) are tinted while odd rows keep the plain background. There is no
 *   inter-row divider any more (the zebra replaces it). The light/dark choice follows the **app**
 *   scheme (the resolved `surface` colour), not the OS setting, so it stays correct even when the
 *   app theme differs from the system: light theme uses a soft warm off-white hardcoded `#FFFBF1`
 *   stripe on the `#F5FBF1` background; dark theme uses a visible `Color.White` overlay (~8%) so
 *   the stripe is clearly lighter than the screen background. The tint is applied at the row's root
 *   `Modifier` (after `fillMaxWidth`, before `padding`) so it spans the row edge to edge, including
 *   the row's own padding; the search-result rows reuse the same treatment;
 * - a compact floating [Surface] panel (Telegram-style: fully-rounded pill, inset from the edges)
 *   holds three destinations — «Настройки», «Провайдеры» and «База» (small icon above a tiny label)
 *   — plus the accent «Новый чат» pill (a [Button] with a leading `Icons.Filled.Add` glyph and the
 *   label) rendered inside the very same bar. There is no separate floating action button any more.
 *   The bar is **always dark** (a fixed `#151B15` container — the dark-theme `surfaceContainer`
 *   value) with fixed light content (`#A6ADA4` labels/icons and the bright `#22C55E`/`#003513`
 *   accent pill), so it renders IDENTICALLY in light and dark themes regardless of the OS setting.
 * - the bar is slim and symmetric: a 40dp pill centred in a 50dp bar leaves an equal ~5dp above and
 *   below the pill, so the bar hugs it instead of leaving dead space around it. On the ~360dp-wide
 *   test device the three weighted destinations must each keep enough width for their full label
 *   (no ellipsis), so the side margins (8dp), the item content padding (1dp), the label size (10sp)
 *   and the accent pill's content padding (16dp) are tuned tight; the accent label is 14sp.
 *
 * Docs:
 * - Window insets: with no `TopAppBar` any more, the search pill lives in the `Scaffold` `topBar`
 *   slot and applies `Modifier.statusBarsPadding()` itself; `Scaffold` then hands the pill's
 *   measured height (status bar + pill) back through `innerPadding`, so the body only applies
 *   `innerPadding` — and there is no double padding —
 *   https://developer.android.com/develop/ui/compose/layouts/insets
 * - `Modifier.statusBarsPadding()` ("Adds padding to accommodate the status bars insets") —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/statusBarsPadding.modifier
 * - `Scaffold` (`bottomBar` slot now carries the whole floating panel; the separate
 *   `floatingActionButton` slot was removed) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/Scaffold.composable
 * - `Button` (accent «Новый чат» pill: `shape` / `colors` / `contentPadding`) —
 *   https://developer.android.com/develop/ui/compose/components/button
 * - `RoundedCornerShape(percent = 50)` (100% rounded corners → stadium/pill; `percent` is the corner
 *   size as a percentage of the shape size, so 50 makes all four corners fully round) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/shape/RoundedCornerShape
 * - `Surface` (neutral floating panel: `shape` / `color` / `tonalElevation` / `shadowElevation`) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Surface(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp,androidx.compose.foundation.BorderStroke,kotlin.Function0)
 * - `Modifier.navigationBarsPadding()` (keeps the floating bar clear of the system nav bar) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/navigationBarsPadding.modifier
 * - `Modifier.clickable` (a plain tap opens the chat; the nested pin/trash buttons consume their own
 *   taps so they never open the chat) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#(androidx.compose.ui.Modifier).clickable(kotlin.Boolean,kotlin.String,androidx.compose.ui.semantics.Role,kotlin.Function0)
 * - `Icon(painter = …)` (pin/delete row actions; the pin toggle swaps between the filled `ic_pin`
 *   and the outlined `ic_pin_outline` drawable) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Icon(androidx.compose.ui.graphics.painter.Painter,kotlin.String,androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Color)
 * - `rememberVectorPainter` (adapts the core-set `Icons.Filled.*` vectors to the `Painter` slots of
 *   the bottom-bar item) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/vector/package-summary#rememberVectorPainter(androidx.compose.ui.graphics.vector.ImageVector)
 * - Vector drawables (`ic_pin`, `ic_pin_outline`, `ic_sections`) —
 *   https://developer.android.com/develop/ui/views/graphics/vector-drawable-resources
 * - `LazyColumn` `contentPadding` (padding around the whole content, added after the content is
 *   clipped — used here for the bottom inset that keeps the last row above the floating FAB/bar) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary#LazyColumn(androidx.compose.ui.Modifier,androidx.compose.foundation.lazy.LazyListState,androidx.compose.foundation.layout.PaddingValues,kotlin.Boolean,androidx.compose.foundation.layout.Arrangement.Vertical,androidx.compose.ui.Alignment.Horizontal,androidx.compose.foundation.gestures.FlingBehavior,kotlin.Boolean,kotlin.Function1)
 * - `AlertDialog` (delete confirmation) —
 *   https://developer.android.com/develop/ui/compose/components/dialog
 * - `BasicTextField` (the editable search pill; see the `ChatSearchField` KDoc for the full
 *   reference links) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/BasicTextField.composable
 * - `Modifier.background(color, shape)` (the zebra stripe; applied before `padding` so it fills the
 *   full row width, and it is only added on tinted rows — non-tinted rows keep the default
 *   transparent background) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#(androidx.compose.ui.Modifier).background(androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Shape)
 * - `Color.luminance()` ("Returns the relative luminance of this color", WCAG 2.0, 0 = black …
 *   1 = white) — reads the resolved `surface` luminance to pick the light `#FFFBF1` stripe vs. the
 *   subtle dark `Color.White` overlay, so the zebra follows the **app** scheme rather than the OS:
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
 * - `ButtonDefaults.buttonColors(containerColor, contentColor, …)` (the fixed bright accent colours) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/ButtonDefaults#buttonColors(androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color)
 * - `itemsIndexed` (gives the row index so the zebra can tint `index % 2 == 1`; used by both the main
 *   list and the cross-chat search results) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary#itemsIndexed(kotlin.collections.List,kotlin.Function2,kotlin.Function2)
 */
@Composable
fun ChatListScreen(
    onChatClick: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProvider: () -> Unit,
    onOpenHome: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Трекер «уже анимированных» строк: каскадный вход проигрывается один раз за визит экрана,
    // а не при каждом возврате элемента в окно во время скролла (D1).
    val rowTracker = remember { EnterAnimationTracker() }

    // Опорное время для относительных меток строк. Снимается один раз на визит экрана: строки в
    // списке не пересчитывают часы сами («сегодня/вчера» важнее точной минуты).
    val now = remember { System.currentTimeMillis() }

    // Чат, ожидающий подтверждения удаления. Меню строки лишь открывает диалог; сама строка из
    // списка удаляется только после подтверждения.
    var pendingDelete by remember { mutableStateOf<ChatListRow?>(null) }

    Scaffold(
        topBar = {
            // No title/header any more: the compact search pill IS the top of the screen. It lives
            // in the `topBar` slot and applies its own `statusBarsPadding()`, so it sits just under
            // the system status bar; Scaffold then pads the content below the pill by the pill's
            // measured height. `statusBarsPadding()` "adds padding to accommodate the status bars
            // insets":
            // https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/statusBarsPadding.modifier
            ChatSearchField(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 6.dp)
                    .height(42.dp)
            )
        },
        bottomBar = {
            ChatListBottomBar(
                onOpenSettings = onOpenSettings,
                onOpenProvider = onOpenProvider,
                onOpenHome = onOpenHome,
                // The accent «Новый чат» pill lives INSIDE the floating bar now (no separate FAB).
                onNewChat = { viewModel.createNewChat(onChatClick) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (state.query.isNotBlank()) {
                    // Non-blank query → the screen becomes a cross-chat search surface. The snippet
                    // is shown as the row's secondary line instead of the last-message preview.
                    ChatSearchResults(
                        results = state.results,
                        isSearching = state.isSearching,
                        onChatClick = onChatClick
                    )
                } else if (state.chats.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_list_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Bottom inset so the last row can scroll fully clear of the floating bar:
                        // 8dp top gap + 50dp bar + 12dp bottom margin = 70dp of chrome, plus ~24dp
                        // for the system navigation-bar inset the bar is lifted above ≈ 94dp.
                        // (Scaffold's `innerPadding` already lifts the list above the bar, so this
                        // is a comfortable trailing gap.) Top padding trimmed to 2dp so the first
                        // row hugs the compact search pill instead of adding extra headroom.
                        contentPadding = PaddingValues(top = 2.dp, bottom = 94.dp)
                    ) {
                        itemsIndexed(
                            items = state.chats,
                            key = { _, row -> row.chat.id }
                        ) { index, row ->
                            // Каскадный вход строки (03-chats.html: `and-fade-up` + задержки). Шапка,
                            // строка поиска и нижняя панель находятся в Scaffold и входной анимации
                            // не имеют.
                            Column(
                                modifier = Modifier.enterAnimation(
                                    index = index,
                                    tracker = rowTracker,
                                    itemKey = row.chat.id
                                )
                            ) {
                                ChatListRow(
                                    row = row,
                                    now = now,
                                    // Zebra striping: 1-based even rows (index 1, 3, …) are tinted.
                                    isZebra = index % 2 == 1,
                                    onClick = { onChatClick(row.chat.id) },
                                    onPin = { viewModel.togglePin(row.chat.id) },
                                    onRequestDelete = { pendingDelete = row }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation (Material3 `AlertDialog`): opening the dialog is all the trash icon does —
    // the chat is removed only on «Удалить»; «Отмена» (and `onDismissRequest`, e.g. tapping outside)
    // merely clears `pendingDelete` and leaves the chat untouched. Docs:
    // https://developer.android.com/develop/ui/compose/components/dialog
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.chat_delete_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    val chatId = row.chat.id
                    pendingDelete = null
                    viewModel.delete(chatId)
                }) {
                    Text(stringResource(R.string.settings_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}
