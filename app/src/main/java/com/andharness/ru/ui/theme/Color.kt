package com.andharness.ru.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand palette for ANDharness, carried over 1:1 from the validated design system
 * (`DESIGN/tokens.css`, roles `--md-*`). The palettes themselves are output of the Google
 * Design MCP `generate_color_scheme` call B — `primaryKey:#22C55E`, `secondaryKey:#38BDF8`,
 * `tertiaryKey:#818CF8`, `schemeVariant:BRAND`, `contrastLevel:0` — for both themes.
 *
 * Decisions baked in here:
 * - D2 — brand palette from the landing (`site/`): green #22C55E action, sky #38BDF8 and
 *   indigo #818CF8 as support accents.
 * - D3 — both themes supported; dark is the default (see [ThemeMode]).
 * - D4 — Material You / dynamic color is OFF: this fixed scheme is always used.
 *
 * Material 3 color roles — https://m3.material.io/styles/color/roles
 * Compose M3 theming — https://developer.android.com/develop/ui/compose/designsystems/material3
 */

// --- Dark theme: DESIGN/tokens.css `:root` (D3 default theme). -----------------------------

private val DarkPrimary = Color(0xFF22C55E)
private val DarkOnPrimary = Color(0xFF003513)
private val DarkPrimaryContainer = Color(0xFF00B753)
private val DarkOnPrimaryContainer = Color(0xFF00270C)
private val DarkInversePrimary = Color(0xFF006E2F) // derived: light-theme primary

private val DarkSecondary = Color(0xFF38BDF8)
private val DarkOnSecondary = Color(0xFF00354A)
private val DarkSecondaryContainer = Color(0xFF1DAFEA)
private val DarkOnSecondaryContainer = Color(0xFF00293B)

private val DarkTertiary = Color(0xFF818CF8)
private val DarkOnTertiary = Color(0xFF000559)
private val DarkTertiaryContainer = Color(0xFF606AD4)
private val DarkOnTertiaryContainer = Color(0xFFFFFFFF)

private val DarkError = Color(0xFFF9605A)
private val DarkOnError = Color(0xFF310002)
private val DarkErrorContainer = Color(0xFF81020F)
private val DarkOnErrorContainer = Color(0xFFFFAEA7)

private val DarkBackground = Color(0xFF09100A) // == surface (M3: background = surface)
private val DarkOnBackground = Color(0xFFE1E7DE) // == onSurface
private val DarkSurface = Color(0xFF09100A)
private val DarkOnSurface = Color(0xFFE1E7DE)
private val DarkSurfaceDim = Color(0xFF09100A)
private val DarkSurfaceBright = Color(0xFF272E27)
private val DarkSurfaceContainerLowest = Color(0xFF000000)
private val DarkSurfaceContainerLow = Color(0xFF0F150F)
private val DarkSurfaceContainer = Color(0xFF151B15)
private val DarkSurfaceContainerHigh = Color(0xFF1B211B)
private val DarkSurfaceContainerHighest = Color(0xFF212721)
private val DarkSurfaceVariant = Color(0xFF414941)
private val DarkOnSurfaceVariant = Color(0xFFA6ADA4)
private val DarkSurfaceTint = Color(0xFF22C55E) // == primary
private val DarkInverseSurface = Color(0xFFF5FBF1)
private val DarkInverseOnSurface = Color(0xFF50574F)
private val DarkOutline = Color(0xFF70776F)
private val DarkOutlineVariant = Color(0xFF434A43)
private val DarkScrim = Color(0xFF000000)

/** Dark scheme from `DESIGN/tokens.css` `:root` (the default theme, D3). */
val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkSurfaceTint,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
)

// --- Light theme: warm palette of plusvibeapi.ru (paper #FFFBF1, ink #2B281B, brand #F6C94C). ---
//
// The LIGHT scheme was recoloured to the warm paper/gold palette of the landing page
// (plusvibeapi.ru). The dark scheme above is untouched. Site tokens in comments.

private val LightPrimary = Color(0xFFF6C94C) // brand gold
private val LightOnPrimary = Color(0xFF2B281B)
private val LightPrimaryContainer = Color(0xFFFFE187)
private val LightOnPrimaryContainer = Color(0xFF3A2C00)
private val LightInversePrimary = Color(0xFF8A6800) // --pv-brand-dark

private val LightSecondary = Color(0xFF7C3AED) // accent violet
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFEDE0FF)
private val LightOnSecondaryContainer = Color(0xFF2A0A6E)

private val LightTertiary = Color(0xFFFB7185) // accent coral
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFFFD9DE)
private val LightOnTertiaryContainer = Color(0xFF5B0A18)

private val LightError = Color(0xFFB12D2C) // KEEP red error roles unchanged
private val LightOnError = Color(0xFFFFF7F6)
private val LightErrorContainer = Color(0xFFF9605A)
private val LightOnErrorContainer = Color(0xFF310002)

private val LightBackground = Color(0xFFFFFBF1) // --pv-paper
private val LightOnBackground = Color(0xFF2B281B) // --pv-ink
private val LightSurface = Color(0xFFFFFBF1)
private val LightOnSurface = Color(0xFF2B281B)
private val LightSurfaceDim = Color(0xFFEFE7D3)
private val LightSurfaceBright = Color(0xFFFFFDF7)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
// Site model: white cards on cream paper. Pure white (#FFFFFF) so section cards / list cards
// using `surfaceContainerLow` separate clearly from the warm `--pv-paper` background (#FFFBF1).
private val LightSurfaceContainerLow = Color(0xFFFFFFFF)
private val LightSurfaceContainer = Color(0xFFFCF8ED) // --pv-surface-subtle
private val LightSurfaceContainerHigh = Color(0xFFF5EFDC)
private val LightSurfaceContainerHighest = Color(0xFFEFE8D2)
private val LightSurfaceVariant = Color(0xFFF1EAD5)
private val LightOnSurfaceVariant = Color(0xFF746E58) // --pv-ink-mute
private val LightSurfaceTint = Color(0xFFF6C94C)
private val LightInverseSurface = Color(0xFF2B281B)
private val LightInverseOnSurface = Color(0xFFF2ECDA)
private val LightOutline = Color(0xFF9B947C) // --pv-ink-faint
private val LightOutlineVariant = Color(0xFFDCD4BD)
private val LightScrim = Color(0xFF000000) // unchanged

/** Light scheme: the warm paper/gold palette of plusvibeapi.ru (dark scheme is unchanged). */
val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightSurfaceTint,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightScrim,
)

// --- Primary CTA brand gradient: DESIGN/tokens.css `--btn-primary-bg` / `--btn-primary-fg`. -----
//
// The three stops are the landing-page CTA gradient (`site/index.html` `.btn--primary`):
// `linear-gradient(135deg, #2EE06A, #22C55E 55%, #16A34A)` — see tokens.css lines 47-52
// (`--brand-green-bright` / `--brand-green` / `--brand-green-deep`). They only ever appear
// through the shared [com.andharness.ru.ui.components.PrimaryGradientButton], never inline.
//
// [OnPrimaryGradient] is the design's `--btn-primary-fg` (#04210F). It is intentionally **not**
// the M3 `onPrimary` role: the gradient is a fixed brand surface, whereas `onPrimary` flips with
// the theme (near-white in light), which would fail AA on the bright green stops. The design
// documents #04210F on the worst stop #16A34A ≈ 5.18:1 (normal text, ≥4.5:1).
val BrandGreenBright = Color(0xFF2EE06A)
val BrandGreen = Color(0xFF22C55E)
val BrandGreenDeep = Color(0xFF16A34A)
val OnPrimaryGradient = Color(0xFF04210F)

// --- Gold CTA brand gradient (LIGHT theme only): plusvibeapi.ru `--pv-brand` family. -----------
//
// The light theme's primary CTA uses the warm gold gradient of plusvibeapi.ru. The dark theme
// keeps the green gradient above. Stops: `--pv-brand-light` #FFE187 → `--pv-brand` #F6C94C →
// a deeper gold stop #D9A92E. [OnBrandGold] is the dark ink on gold (`--pv-ink`-like #2B281B).
val BrandGoldBright = Color(0xFFFFE187) // --pv-brand-light
val BrandGold = Color(0xFFF6C94C) // --pv-brand
val BrandGoldDeep = Color(0xFFD9A92E) // deeper gradient stop
val OnBrandGold = Color(0xFF2B281B) // dark ink on gold

/**
 * Chat bubble pair, copied from the `--bubble-user-bg` / `--bubble-user-fg` tokens in
 * `DESIGN/tokens.css`. These are intentionally **not** M3 roles: the dark-theme user bubble
 * cannot use the acid `primary`/`primaryContainer` (`#22C55E` / `#00B753`) as a large fill, so
 * the design derives a desaturated forest green (30% brand green + 70% dark surface, `#104623`)
 * with `onSurface` as the foreground (8.68:1, WCAG AAA).
 *
 * Light theme (изменено по запросу пользователя): пузырь пользователя берёт золото сайта
 * (plusvibeapi.ru) — брендовый стоп [BrandGold] `#F6C94C` (`--pv-brand`) с тёмными чернилами
 * [OnBrandGold] `#2B281B`, согласованно с золотым CTA светлой темы. Тёмная тема **не меняется**:
 * там остаётся приглушённый `#104623` со светлым текстом.
 *
 * Consumed by the chat components in the next step; defined here so the bubble pair lives with
 * the rest of the theme rather than as a hardcoded colour in a screen.
 */
data class BubbleColors(
    val userBackground: Color,
    val userForeground: Color,
)

val DarkBubbleColors = BubbleColors(
    userBackground = Color(0xFF104623), // color-mix(30% #22C55E, 70% #09100A)
    userForeground = DarkOnSurface, // #E1E7DE
)

val LightBubbleColors = BubbleColors(
    userBackground = BrandGold, // #F6C94C — сайт `--pv-brand`, золото светлой темы
    userForeground = OnBrandGold // #2B281B — тёмные чернила на золотой заливке
)

/**
 * Цвет текста ответа ассистента на нейтральной поверхности бабла (`surfaceVariant`).
 * Это явный выбор пользователя (запрос на читаемость в чате): светлая тема — #333333,
 * тёмная — #F6F6F6. Значения не являются ролями M3 и заданы как фиксированные чернила;
 * активная тема определяется по яркости `surface` (см. `MessageBubble`), как в `AccentPanel`.
 */
val AssistantBubbleTextDark = Color(0xFFF6F6F6)
val AssistantBubbleTextLight = Color(0xFF333333)

/**
 * Цвет транзиентной строки действия агента («Открываю приложение…», «Нажимаю…»). Раньше строка
 * рисовалась `primary`, который в светлой теме — золото `#F6C94C` (жёлтый, плохо читаемый на
 * бумажном фоне). По запросу пользователя строка перекрашена в спокойный серо-синий в светлой теме
 * ([AgentActionTextLight] `#5A6B7C`, контраст ≈ 5.4:1 на `#FFFBF1`) и в приглушённый светло-серый в
 * тёмной ([AgentActionTextDark] `#B8BFC6`, контраст ≈ 9:1 на `#09100A`); стиль — курсив. Значения —
 * фиксированные, вне ролей M3; активная тема определяется по яркости `surface`, как в
 * `MessageBubble`.
 */
val AgentActionTextLight = Color(0xFF5A6B7C)
val AgentActionTextDark = Color(0xFFB8BFC6)

/**
 * Цвет ссылок внутри ответа ассистента. Раньше ссылки рисовались `primary`, который в светлой теме —
 * золото `#F6C94C` (жёлтый, почти неразличимый на бумажном фоне). По запросу пользователя ссылки
 * перекрашены в обычный синий в обеих темах: светлая — [ChatLinkLight] `#1565C0` (контраст ≈ 5.8:1 на
 * `#FFFBF1`), тёмная — [ChatLinkDark] `#8AB4F8` (контраст ≈ 8:1 на `#09100A`). Значения фиксированные,
 * вне ролей M3; активная тема определяется по яркости `surface`, как в `MessageBubble`.
 */
val ChatLinkLight = Color(0xFF1565C0)
val ChatLinkDark = Color(0xFF8AB4F8)

// --- Fixed (theme-independent) tokens: moved out of inline `Color(0x…)` literals in screens. -----
/**
 * Intentionally fixed colour tokens. The surfaces below deliberately do NOT follow the active
 * light/dark scheme — they must render identically in both themes: the always-dark chat-list
 * bottom bar, chat-name pill and home status card; the light-theme chat-list zebra; the forced
 * near-black ink on the white «Обновления» card; the brand-bordeaux «Все разрешения» button.
 * They live here as named tokens instead of inline `Color(0x…)` literals at the call sites.
 * Never branch them on dark/light and do not replace them with M3 roles — that would change the
 * intended fixed look. Values are byte-identical to the literals they replaced.
 */

/** Chat-list zebra band in the LIGHT theme: soft cream `#FFFBF1` (same as the light background). */
val ChatRowZebraLight = Color(0xFFFFFBF1)

/** Chat-list plain (non-banded) LIGHT row fill: pure white, keeps the cream band visible. */
val ChatRowZebraPlain = Color(0xFFFFFFFF)

/** Always-dark surface: chat-list bottom bar, chat-name pill, home status card (`#151B15`). */
val AlwaysDarkSurface = Color(0xFF151B15)

/** Light ink on the always-dark surface (chat-name pill text; dark-theme `onSurface` `#E1E7DE`). */
val AlwaysDarkSurfaceInk = Color(0xFFE1E7DE)

/** Ink for nav icons/labels on the always-dark bar (dark-theme `onSurfaceVariant` `#A6ADA4`). */
val AlwaysDarkBarInk = Color(0xFFA6ADA4)

/** Accent «Новый чат» pill fill on the always-dark bar (dark-theme `primary` `#22C55E`). */
val AlwaysDarkBarAccent = Color(0xFF22C55E)

/** Content colour on the always-dark accent pill (dark-theme `onPrimary` `#003513`). */
val AlwaysDarkBarAccentContent = Color(0xFF003513)

/** Near-black ink on the forced-white «Обновления» card (`#1A1A1A`), light theme only. */
val UpdatesInkOnWhite = Color(0xFF1A1A1A)

/** Bordeaux fill of the «Все разрешения» tonal button (`#7B1E2B`). */
val SpecialAccessButtonFill = Color(0xFF7B1E2B)
