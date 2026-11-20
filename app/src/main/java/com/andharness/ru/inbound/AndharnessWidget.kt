package com.andharness.ru.inbound

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.andharness.ru.R
import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.ui.theme.DarkColorScheme
import com.andharness.ru.ui.theme.LightColorScheme
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Home-screen widget (update.md idea #10): the app name, the newest chats (tap one to open it) and
 * a brand-green «Новый чат» action.
 *
 * Built with Jetpack Glance:
 * https://developer.android.com/develop/ui/compose/glance/create-app-widget
 *
 * Glance composition does not run through Hilt, so the chat titles are read through an [EntryPoint]
 * over the singleton component. `provideGlance` runs on the main thread, hence the explicit
 * `Dispatchers.IO` for the (single, cheap) database read; any failure degrades to the header +
 * «Новый чат» rather than showing fake or stale titles.
 *
 * Freshness: the composition itself observes `ChatRepository.observeChats()`, so it keeps updating
 * while its session is alive; [com.andharness.ru.AndharnessApplication] additionally collects the
 * same feed on its application scope and calls `updateAll` to wake the widget / reload its initial
 * data whenever the app process is running.
 */
class AndharnessWidget : GlanceAppWidget() {

    /**
     * Exact sizing: the content is (re)run for the widget's real bound size, so `LocalSize.current`
     * reports the actual size rather than the declared `minHeight`. With the default
     * [SizeMode.Single] `LocalSize` returns only the metadata minimum (110dp here) and the row count
     * would collapse to one; [SizeMode.Exact] is the documented way to get the real size.
     * https://developer.android.com/develop/ui/compose/glance/build-ui#define-the-sizemode
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    /**
     * Loads the newest chats, then renders them. The initial read is a suspend call before
     * [provideContent] (the documented place for data loading); the composition then keeps observing
     * [ChatRepository.observeChats], because `update`/`updateAll` do not restart `provideGlance` once
     * it is running.
     * https://developer.android.com/reference/kotlin/androidx/glance/appwidget/GlanceAppWidget#provideGlance(android.content.Context,androidx.glance.GlanceId)
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialChats = withContext(Dispatchers.IO) { recentChats(context) }
        provideContent {
            val repository = rememberChatRepository()
            val chatsFlow = remember(repository) { repository.observeChats() }
            val chats by chatsFlow.collectAsState(initial = initialChats)
            WidgetContent(recentChats = chats.take(MAX_RECENT_CHATS))
        }
    }

    private suspend fun recentChats(context: Context): List<ChatEntity> = runCatching {
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
            .chatRepository()
            .observeChats()
            .first()
            .take(MAX_RECENT_CHATS)
    }.getOrDefault(emptyList())
}

/**
 * Resolves the singleton [ChatRepository] for the composition. Glance is not a Hilt-aware component,
 * so the repository is pulled through the [WidgetEntryPoint] bridge; `remember` keeps the entry-point
 * lookup off the recomposition path.
 */
@Composable
private fun rememberChatRepository(): ChatRepository {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).chatRepository()
    }
}

/**
 * The widget composition. The theme is not left to the launcher: [WidgetColorProviders] is built
 * explicitly from the app's own Material 3 schemes ([LightColorScheme] / [DarkColorScheme], the
 * brand green palette), so the widget matches the app in both day and night mode instead of falling
 * back to Glance's baseline colours.
 *
 * Layout: a rounded card with a header («Меха» + muted subtitle) and a brand-green «Новый чат» pill
 * on the right, then up to [MAX_RECENT_CHATS] chat cards (bold one-line title + muted relative time);
 * with no chats a friendly empty state replaces the list. The row count is derived from the actual
 * widget height ([LocalSize]) so the content fits without clipping at the 3x2 default size and grows
 * when the user resizes the widget.
 */
@Composable
private fun WidgetContent(recentChats: List<ChatEntity>) {
    val context = LocalContext.current
    val availableHeight = LocalSize.current.height
    val now = System.currentTimeMillis()

    GlanceTheme(colors = WidgetColorProviders) {
        val colors = GlanceTheme.colors
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(colors.surface)
                .cornerRadius(CONTAINER_CORNER_RADIUS)
                .padding(CONTAINER_PADDING),
            contentAlignment = Alignment.TopStart
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Header(context = context, colors = colors)
                if (recentChats.isEmpty()) {
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = context.getString(R.string.widget_no_chats),
                        style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp)
                    )
                } else {
                    Spacer(GlanceModifier.height(8.dp))
                    val rows = visibleRowCount(availableHeight, recentChats.size)
                    recentChats.take(rows).forEachIndexed { index, chat ->
                        if (index > 0) Spacer(GlanceModifier.height(ROW_SPACING))
                        ChatRow(context = context, chat = chat, now = now, colors = colors)
                    }
                }
            }
        }
    }
}

/** App name + muted subtitle on the left, the brand-green «Новый чат» pill on the right. */
@Composable
private fun Header(context: Context, colors: ColorProviders) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = context.getString(R.string.app_name),
                maxLines = 1,
                style = TextStyle(
                    color = colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = context.getString(R.string.widget_subtitle),
                maxLines = 1,
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp),
                modifier = GlanceModifier.padding(top = 1.dp)
            )
        }
        Box(
            modifier = GlanceModifier
                .background(colors.primary)
                .cornerRadius(CTA_CORNER_RADIUS)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(actionStartActivity(InboundIntents.newChat(context))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = context.getString(R.string.widget_new_chat),
                maxLines = 1,
                style = TextStyle(
                    color = colors.onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

/** One recent chat: tappable card, bold single-line title, muted relative time on the right. */
@Composable
private fun ChatRow(context: Context, chat: ChatEntity, now: Long, colors: ColorProviders) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(colors.surfaceVariant)
            .cornerRadius(ROW_CORNER_RADIUS)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .clickable(actionStartActivity(InboundIntents.openChat(context, chat.id))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = chat.title,
            maxLines = 1,
            style = TextStyle(
                color = colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = GlanceModifier.defaultWeight()
        )
        Text(
            text = formatWidgetTime(chat.updatedAt, now),
            maxLines = 1,
            style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp),
            modifier = GlanceModifier.padding(start = 8.dp)
        )
    }
}

/**
 * How many chat cards fit in [availableHeightDp] without clipping. At the 3x2 default
 * (≈223dp actual on the test launcher) this yields four rows; resizing larger grows up to
 * [MAX_RECENT_CHATS] and anything smaller clamps to at least one row. When the real size is unknown
 * (0 or negative — e.g. a preview without provider info) [DEFAULT_ROWS_WHEN_UNKNOWN] is used instead
 * of collapsing to a single row.
 *
 * Deliberately plain `Float` dp arithmetic with no Android/Compose types, so it is unit-testable on
 * the JVM.
 */
internal fun visibleRowCountForHeight(availableHeightDp: Float, total: Int): Int {
    if (total <= 0) return 0
    val cap = total.coerceAtMost(MAX_RECENT_CHATS)
    if (availableHeightDp <= 0f) return DEFAULT_ROWS_WHEN_UNKNOWN.coerceAtMost(cap)
    val rows = ((availableHeightDp - CHROME_HEIGHT_DP) / ROW_PITCH_DP).toInt()
    return rows.coerceIn(1, cap)
}

/** [Dp] wrapper for the composition; the math itself lives in [visibleRowCountForHeight]. */
private fun visibleRowCount(availableHeight: Dp, total: Int): Int =
    visibleRowCountForHeight(availableHeight.value, total)

/** Resolves one M3 role to a day/night Glance [ColorProvider]. */
private fun dayNight(role: (ColorScheme) -> Color): ColorProvider =
    androidx.glance.color.ColorProvider(
        day = role(LightColorScheme),
        night = role(DarkColorScheme)
    )

/**
 * Chat-card background. Glance's scheme exposes no `surfaceContainer` role, so the chip/card token
 * from `ui/theme/Color.kt` is used directly: `LightSurfaceContainer` `#E9F0E6` by day,
 * `DarkSurfaceContainer` `#151B15` at night — a subtle step above the container surface rather than
 * the much louder `surfaceVariant` (`#DDE5DA` / `#414941`), which would read as heavy grey blocks.
 * The `surfaceVariant` scheme role is intentionally overridden with this pair.
 */
private val CardSurfaceColor: ColorProvider = androidx.glance.color.ColorProvider(
    day = Color(0xFFE9F0E6),
    night = Color(0xFF151B15)
)

/**
 * Widget colours, resolved from the app's own Material 3 schemes at render time. Glance's
 * `ColorProvider(day, night)` picks the side from the host configuration's night mode
 * (`Configuration.UI_MODE_NIGHT_MASK`), so this follows the device day/night setting. The app's own
 * `ThemeStore` default (dark) is an in-app override and is intentionally not consulted here: honouring
 * it would require re-rendering the widget on every theme toggle, and dark/day follows the launcher.
 *
 * Roles used by the composition: `surface` (card), `onSurface` (titles), `surfaceVariant` (chat-card
 * background, overridden with [CardSurfaceColor]), `onSurfaceVariant` (subtitle/time),
 * `primary`/`onPrimary` (the «Новый чат» pill, brand green with the design's dark ink — see
 * `ui/theme/Color.kt`). Unused roles are still mapped so the scheme is complete and future additions
 * stay on-brand.
 */
private val WidgetColorProviders: ColorProviders = colorProviders(
    primary = dayNight { it.primary },
    onPrimary = dayNight { it.onPrimary },
    primaryContainer = dayNight { it.primaryContainer },
    onPrimaryContainer = dayNight { it.onPrimaryContainer },
    secondary = dayNight { it.secondary },
    onSecondary = dayNight { it.onSecondary },
    secondaryContainer = dayNight { it.secondaryContainer },
    onSecondaryContainer = dayNight { it.onSecondaryContainer },
    tertiary = dayNight { it.tertiary },
    onTertiary = dayNight { it.onTertiary },
    tertiaryContainer = dayNight { it.tertiaryContainer },
    onTertiaryContainer = dayNight { it.onTertiaryContainer },
    error = dayNight { it.error },
    errorContainer = dayNight { it.errorContainer },
    onError = dayNight { it.onError },
    onErrorContainer = dayNight { it.onErrorContainer },
    background = dayNight { it.background },
    onBackground = dayNight { it.onBackground },
    surface = dayNight { it.surface },
    onSurface = dayNight { it.onSurface },
    surfaceVariant = CardSurfaceColor,
    onSurfaceVariant = dayNight { it.onSurfaceVariant },
    outline = dayNight { it.outline },
    inverseOnSurface = dayNight { it.inverseOnSurface },
    inverseSurface = dayNight { it.inverseSurface },
    inversePrimary = dayNight { it.inversePrimary },
    widgetBackground = dayNight { it.surface },
)

private val CONTAINER_PADDING = 12.dp
private val CONTAINER_CORNER_RADIUS = 20.dp
private val ROW_CORNER_RADIUS = 12.dp
private val CTA_CORNER_RADIUS = 16.dp
private val ROW_SPACING = 6.dp

/** Height of everything except the rows (container padding + header + the gap above the list). */
private const val CHROME_HEIGHT_DP = 70f

/** One chat card plus [ROW_SPACING]; used to decide how many cards fit. */
private const val ROW_PITCH_DP = 38f

/** Keeps the widget readable at its smallest size: only the newest chats are shown. */
private const val MAX_RECENT_CHATS = 5

/** Rows rendered when the real bound size is unavailable (preview); never collapse to one. */
private const val DEFAULT_ROWS_WHEN_UNKNOWN = 3
