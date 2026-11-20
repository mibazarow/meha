package com.andharness.ru

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.data.assist.OverlayAssistantStore
import com.andharness.ru.data.clipboard.ClipboardForegroundCapture
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.prefs.LicenseStore
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.locale.AppLocale
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.ui.ActiveChatTracker
import com.andharness.ru.inbound.InboundIntents
import com.andharness.ru.service.OverlayAssistantService
import com.andharness.ru.ui.navigation.AppNavHost
import com.andharness.ru.ui.navigation.ChatDeepLink
import com.andharness.ru.ui.navigation.DeepLinkRouting
import com.andharness.ru.ui.navigation.LicenseGate
import com.andharness.ru.ui.theme.Theme
import com.andharness.ru.ui.theme.ThemeStore
import com.andharness.ru.ui.theme.isLightSystemBarAppearance
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Single-activity host. Handles the notification deep-link (SPEC §20) both on cold start
 * ([onCreate] → [Intent]) and while the app is already open ([onNewIntent]); the manifest declares
 * `launchMode="singleTask"` so an already-running instance receives [onNewIntent] instead of a new
 * Activity being created:
 * https://developer.android.com/guide/topics/manifest/activity-element#lmode
 *
 * The launch intent is read on EVERY [onCreate] (not only when `savedInstanceState == null`), so a
 * deep link delivered while the process was alive is never lost; [handledDeepLinkKey] makes the
 * handling one-shot so a rotation does not navigate a second time.
 *
 * It extends [AppCompatActivity] (a superset of `FragmentActivity`) so the AppCompat per-app locale
 * APIs (`AppCompatDelegate.setApplicationLocales`) apply the chosen UI language on Android 12 and
 * lower too; on Android 13+ those APIs delegate to the framework `LocaleManager`.
 * https://developer.android.com/guide/topics/resources/app-languages
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private companion object {
        const val STATE_HANDLED_DEEP_LINK = "state_handled_deep_link"
        const val TAG = "MainActivity"
    }

    @Inject
    lateinit var chatRepository: ChatRepository

    /** Persisted theme selection (D3); observed below so a toggle recomposes the whole app. */
    @Inject
    lateinit var themeStore: ThemeStore

    /** Persisted EULA acceptance; read synchronously to pick the very first destination. */
    @Inject
    lateinit var licenseStore: LicenseStore

    /**
     * Persisted settings; read synchronously for the per-app locale. `AppCompatDelegate`'s
     * `setApplicationLocales` is a no-op without an active AppCompat Activity on Android 13+
     * (it needs the framework `LocaleManager` from an attached delegate), so the stored/default
     * language is (re)applied here, on the AppCompatActivity, before the UI composes.
     */
    @Inject
    lateinit var settingsStore: LlmSettingsStore

    /** Persisted «Плавающий помощник» switch, reconciled with the live service on foregrounding. */
    @Inject
    lateinit var overlayAssistantStore: OverlayAssistantStore

    /**
     * Best-effort clipboard-history capture. Android 10+ forbids background clipboard reads
     * (https://developer.android.com/about/versions/10/privacy/changes#clipboard-data), so the
     * history is topped up at the one moment the app legitimately holds the clipboard: while it is in
     * the foreground with window focus (see [onWindowFocusChanged]).
     */
    @Inject
    lateinit var clipboardForegroundCapture: ClipboardForegroundCapture

    /** The chat a notification asked us to open, or null for the normal start destination. */
    private val deepLink = MutableStateFlow<ChatDeepLink?>(null)

    /**
     * Identity of the last deep link this instance navigated for, so a recreation (rotation /
     * process-death restore) does not re-run the same navigation and wipe the restored back stack.
     * A genuine new tap arrives through [onNewIntent], which clears it before handling (§20.4).
     */
    private var handledDeepLinkKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Per-app language (RU/EN): ensure the stored/default language is applied before the first
        // composition. On a first launch this sets Russian (the app default) even on a device whose
        // system language is English. On Android 13+ the Application-level call is a no-op (no
        // attached AppCompat delegate yet), so it is (re)applied here on the AppCompatActivity;
        // once a locale is set this is a no-op. https://developer.android.com/guide/topics/resources/app-languages
        runCatching { AppLocale.ensureDefault(settingsStore.currentLanguage()) }
            .onFailure { Log.w(TAG, "Applying the per-app locale failed: ${it.message}") }
        enableEdgeToEdge()
        handledDeepLinkKey = savedInstanceState?.getString(STATE_HANDLED_DEEP_LINK)
        // Always read the launch intent — the key guard above (not `savedInstanceState`) decides
        // whether this is a fresh deep link or a re-delivery on recreation (§20.4).
        handleDeepLink(intent)
        // Первый экран: выбор языка → лицензия → настройка модели. Гейт завершения первого запуска —
        // сохранённое подключение к модели ([LlmSettingsStore.isConfigured()]: непустой эндпоинт).
        // Пока его нет, КАЖДЫЙ запуск начинается с выбора языка (шаг языка не пропускается, даже если
        // язык уже выбирали); после сохранения подключения — сразу главный экран (чаты). Значение
        // читается синхронно один раз до первой композиции, чтобы не мигало неверным стартовым
        // экраном (таблица настроек — одна строка). `accepted(...)` по-прежнему решает, показывать
        // ли EULA после выбора языка. Порядок решается в чистом [LicenseGate] (см. его тесты).
        // `runBlocking` — одноразовое малое чтение до первого кадра; официальная документация:
        // https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html
        val licenseAccepted = licenseStore.accepted(LicenseStore.LICENSE_VERSION)
        val modelConfigured = runCatching {
            runBlocking { settingsStore.isConfigured().first() }
        }.getOrDefault(false)
        val startDestination = LicenseGate.startDestination(modelConfigured = modelConfigured)
        setContent {
            val link by deepLink.collectAsStateWithLifecycle()
            val themeMode by themeStore.mode.collectAsStateWithLifecycle()
            val darkTheme = themeMode.isDark(isSystemInDarkTheme())
            // The status/navigation bar icons must invert with the IN-APP theme, not the OS one:
            // `enableEdgeToEdge()` above resolved the initial appearance from the system, so a user
            // toggling the app theme (D3) left e.g. light icons on a light bar. Re-apply on every
            // resolved-theme change using the insets controller (the modern replacement for
            // re-calling `enableEdgeToEdge(SystemBarStyle…)`, which is deprecated):
            // https://developer.android.com/reference/androidx/core/view/WindowInsetsControllerCompat#setAppearanceLightStatusBars(boolean)
            // The bars themselves stay transparent (already set by `enableEdgeToEdge()`), so the
            // theme surface shows through — no hardcoded bar color is introduced here.
            LaunchedEffect(darkTheme) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    val lightBars = isLightSystemBarAppearance(darkTheme)
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
            Theme(themeMode = themeMode) {
                AppNavHost(
                    deepLink = link,
                    // Clear the one-shot link once the nav host has acted on it, so a later
                    // recreation does not re-navigate; a new notification tap carries a fresh nonce.
                    onDeepLinkHandled = { deepLink.value = null },
                    startDestination = startDestination,
                    // Шаг «выбор языка» виден лишь на первом запуске; после выбора уходим на
                    // лицензию (или сразу на онбординг/главный, если лицензия уже принята).
                    languageChosenDestination = LicenseGate.afterLanguage(licenseAccepted),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // BUG-030: a switch left ON by a previous process must correspond to a live bubble. Reuse
        // the same idempotent reconciliation the Application runs at process start — this also
        // covers a process that stayed alive while only the service died. Best-effort: a failure
        // here must never prevent the app from coming to the foreground.
        try {
            OverlayAssistantService.reconcile(this, overlayAssistantStore, licenseStore)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay assistant reconciliation failed: ${e.message}")
        }
        // update.md bug #5: publish visibility so chat-related notifications can be suppressed while
        // the user is actually looking at the app.
        ActiveChatTracker.onAppForegrounded()
        // Clipboard history: onStart is the earliest foreground moment; the actual read is gated by
        // the platform (hasPrimaryClip returns false without input focus), so [onWindowFocusChanged]
        // below is the moment capture usually succeeds. Consecutive duplicates are dropped by the
        // store, so capturing twice is harmless.
        clipboardForegroundCapture.captureIfAllowed()
    }

    /**
     * On Android 10+ the clipboard is readable only while the app is the default IME or has input
     * focus; [onWindowFocusChanged] with `hasFocus = true` is the documented point at which the
     * activity gains that focus, so this is where the foreground capture reliably succeeds:
     * https://developer.android.com/reference/android/app/Activity#onWindowFocusChanged(boolean)
     * Best-effort and non-throwing — it must never interfere with the UI.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) clipboardForegroundCapture.captureIfAllowed()
    }

    override fun onStop() {
        super.onStop()
        // A configuration change (rotation) stops the activity without the user leaving the app, so
        // it must not be mistaken for the user leaving the app.
        if (!isChangingConfigurations) {
            ActiveChatTracker.onAppBackgrounded()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_HANDLED_DEEP_LINK, handledDeepLinkKey)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A fresh delivery while the app is already running (notification content intent with
        // FLAG_ACTIVITY_SINGLE_TOP, or `am start`): even an identical payload is a new user tap,
        // so forget the previously handled key and navigate again.
        handledDeepLinkKey = null
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        // Единая точка лицензионного гейта (EULA): пока соглашение не принято, ни deep link
        // уведомления, ни входящее намерение (share / process text / плитка / виджет) не
        // обрабатываются — приложение обязано остаться на LICENSE. Поведение — DROP, не HOLD:
        // действие не откладывается и не повторяется после принятия. Ключ отброшенного намерения
        // всё равно запоминается, чтобы повторное чтение launch intent при пересоздании Activity
        // не «оживило» его уже после принятия лицензии.
        // https://developer.android.com/develop/ui/compose/navigation#deeplinks
        // https://developer.android.com/reference/android/app/Activity#onNewIntent(android.content.Intent)
        if (!LicenseGate.inboundAllowed(licenseStore.accepted(LicenseStore.LICENSE_VERSION))) {
            inboundIntentKey(intent)?.let { handledDeepLinkKey = it }
            return
        }
        if (handleInboundIntent(intent)) return
        val chatId = intent
            ?.getLongExtra(NotificationHelper.EXTRA_CHAT_ID, -1L)
            ?.takeIf { it != -1L }
            ?: return
        val messageId = intent
            .getLongExtra(NotificationHelper.EXTRA_MESSAGE_ID, -1L)
            .takeIf { it != -1L }
        val key = DeepLinkRouting.key(chatId, messageId)
        if (key == handledDeepLinkKey) return
        handledDeepLinkKey = key

        lifecycleScope.launch {
            val exists = chatRepository.observeChat(chatId).first() != null
            if (exists) {
                deepLink.value = ChatDeepLink(chatId, messageId)
            } else {
                // §20.4: deleted chat → honest fallback to the chat list, never an empty chat.
                deepLink.value = null
                Toast.makeText(
                    this@MainActivity,
                    "Чат, из которого пришло уведомление, удалён",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Стабильный ключ входящего намерения — тот же формат, что используют ветки ниже:
     * [InboundIntents.EXTRA_INBOUND_KEY] для inbound-намерений и
     * `chatId:messageId` для deep link уведомления ([NotificationHelper.EXTRA_CHAT_ID] /
     * [NotificationHelper.EXTRA_MESSAGE_ID]). null — намерение не является входящим.
     */
    private fun inboundIntentKey(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == InboundIntents.ACTION_INBOUND) {
            return intent.getStringExtra(InboundIntents.EXTRA_INBOUND_KEY)
        }
        val chatId = intent.getLongExtra(NotificationHelper.EXTRA_CHAT_ID, -1L)
        if (chatId == -1L) return null
        val messageId = intent.getLongExtra(NotificationHelper.EXTRA_MESSAGE_ID, -1L)
        return DeepLinkRouting.key(chatId, messageId.takeIf { it != -1L })
    }

    /**
     * Inbound entry points (update.md idea #10). The share / process-text receivers and the Quick
     * Settings tile / widget all end up here with a private action:
     * - [InboundIntents.EXTRA_OPEN_CHAT_ID] — a chat the receiver already created and seeded
     *   (share, process text, or a widget tap on a specific chat);
     * - [InboundIntents.EXTRA_NEW_CHAT] — a request for a brand-new empty chat (tile tap, widget
     *   «Новый чат»).
     *
     * Returns true when the Intent was one of those, so the notification path below is skipped.
     * The one-shot key mirrors the notification guard: the key is derived from the Intent itself and
     * remembered in instance state, so a rotation re-reads the same Intent without creating a second
     * chat, while a genuine re-delivery through [onNewIntent] clears it and acts again.
     */
    private fun handleInboundIntent(intent: Intent?): Boolean {
        if (intent == null || intent.action != InboundIntents.ACTION_INBOUND) return false
        val key = intent.getStringExtra(InboundIntents.EXTRA_INBOUND_KEY) ?: return false
        val openChatId = intent.getLongExtra(InboundIntents.EXTRA_OPEN_CHAT_ID, -1L)
        val wantsNewChat = intent.getBooleanExtra(InboundIntents.EXTRA_NEW_CHAT, false)
        if (openChatId == -1L && !wantsNewChat) return false
        if (key == handledDeepLinkKey) return true
        handledDeepLinkKey = key

        lifecycleScope.launch {
            val chatId = if (openChatId != -1L) {
                openChatId.takeIf { chatRepository.observeChat(it).first() != null }
            } else {
                chatRepository.createChat("Новый чат")
            }
            if (chatId != null) {
                deepLink.value = ChatDeepLink(chatId)
            } else {
                // The target chat vanished (deleted between the tap and the hand-off): fall back to
                // the chat list instead of opening an empty screen.
                Toast.makeText(this@MainActivity, "Чат удалён", Toast.LENGTH_LONG).show()
            }
        }
        return true
    }
}
