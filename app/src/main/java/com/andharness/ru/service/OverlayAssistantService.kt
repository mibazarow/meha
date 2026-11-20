package com.andharness.ru.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.andharness.ru.data.assist.OverlayAgentRunner
import com.andharness.ru.data.assist.OverlayAssistantStore
import com.andharness.ru.data.chat.TopicChatRouter
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.prefs.LicenseStore
import com.andharness.ru.domain.assist.overlay.OverlayAssistantPolicy
import com.andharness.ru.domain.assist.overlay.OverlayQuickAsk
import com.andharness.ru.domain.assist.overlay.OverlayQuickAskState
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.service.overlay.OverlayAgentHandoff
import com.andharness.ru.service.overlay.OverlayBubbleController
import com.andharness.ru.service.overlay.OverlayNotificationFactory
import com.andharness.ru.service.overlay.OverlayPanelController
import com.andharness.ru.service.overlay.OverlayScreenContextReader
import com.andharness.ru.service.overlay.OverlayWindowHost
import com.andharness.ru.ui.theme.ThemeStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * «Плавающий помощник»: the foreground service that owns the floating bubble.
 *
 * It adds a `TYPE_APPLICATION_OVERLAY` window (plus, on demand, a second focusable window for the
 * quick-ask panel) and drives the existing agent loop directly: [OverlayAgentHandoff] routes the
 * request (pure `OverlayChatRouting`/`OverlayRequestClassifier`):
 *  - an ON-SCREEN question → the one persistent «Распознавание экрана» chat
 *    ([TopicChatRouter.ensure], topic `screen`) seeded with the current-screen context;
 *  - any other request → a NEW chat created per request, with NO screen read.
 * Both branches run through [com.andharness.ru.data.assist.OverlayAgentRunner], which calls the same
 * [com.andharness.ru.domain.agent.AgentOrchestrator.run] entry point scheduled tasks use, so the
 * turn runs even when no Activity is in the foreground (BUG-041). Opening the chat is only a
 * best-effort convenience on top of that.
 *
 * This class is a thin coordinator: the window mechanics live in [OverlayBubbleController] /
 * [OverlayPanelController], screen capture in
 * [OverlayScreenContextReader], the agent hand-off in [OverlayAgentHandoff], the foreground
 * notification in [OverlayNotificationFactory] and the window/type resolution in
 * [OverlayWindowHost]. (Overlay dictation was removed in redesign §2 phase 5.)
 *
 * Foreground service type: `specialUse` — the documented type for a valid use case not covered by
 * any other type; it needs no type-specific runtime prerequisite beyond
 * `FOREGROUND_SERVICE_SPECIAL_USE`, and the manifest property below states the reason.
 * https://developer.android.com/develop/background-work/services/fgs/service-types#special
 * https://developer.android.com/about/versions/14/changes/fgs-types-required
 *
 * Starting an Activity from here is allowed because the user has granted `SYSTEM_ALERT_WINDOW`,
 * which is an explicit exemption from the Android 10+ background-activity-launch restrictions; the
 * overlay window is also visible at that moment (required on Android 15 for apps that target 35).
 * https://developer.android.com/guide/components/activities/secure-bal
 * https://developer.android.com/about/versions/15/behavior-changes-15#fgs-saw
 *
 * Overlay, service and window APIs:
 * - `SYSTEM_ALERT_WINDOW` + `Settings.canDrawOverlays` —
 *   https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)
 * - `TYPE_APPLICATION_OVERLAY` (API 26+; `TYPE_PHONE` et al. are forbidden on O+) —
 *   https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
 * - foreground service types — https://developer.android.com/develop/background-work/services/fgs/service-types
 */
@AndroidEntryPoint
class OverlayAssistantService : Service() {

    @Inject lateinit var notifications: NotificationHelper
    @Inject lateinit var store: OverlayAssistantStore
    @Inject lateinit var licenseStore: LicenseStore
    @Inject lateinit var topicChatRouter: TopicChatRouter
    @Inject lateinit var overlayAgentRunner: OverlayAgentRunner
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var themeStore: ThemeStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val quickAsk = MutableStateFlow(OverlayQuickAskState())

    private lateinit var windows: OverlayWindowHost
    private lateinit var bubble: OverlayBubbleController
    private lateinit var panel: OverlayPanelController
    private lateinit var screenContext: OverlayScreenContextReader
    private lateinit var handoff: OverlayAgentHandoff
    private lateinit var overlayNotification: OverlayNotificationFactory

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windows = OverlayWindowHost(this)
        screenContext = OverlayScreenContextReader(packageName)
        handoff = OverlayAgentHandoff(this, topicChatRouter, overlayAgentRunner, screenContext, chatRepository, CHAT_TITLE)
        overlayNotification = OverlayNotificationFactory(this)
        bubble = OverlayBubbleController(
            context = this,
            themeStore = themeStore,
            windows = windows,
            onTap = { openPanel() },
            onLongPress = { closeAssistant() },
            onAddFailed = { stopSelf() },
        )
        panel = OverlayPanelController(
            context = this,
            themeStore = themeStore,
            windows = windows,
            quickAsk = quickAsk,
            onInputChange = { quickAsk.value = OverlayQuickAsk.onInput(quickAsk.value, it) },
            onSubmit = { submit() },
            onClose = { hidePanel() },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAssistant(startId)
            ACTION_STOP -> {
                // Reached from the notification "Закрыть" action. Persist OFF so the Settings toggle
                // cannot stay ON while the bubble is gone (the same honesty rule BUG-030 enforces at
                // process start).
                store.setEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            // No action (e.g. a sticky re-delivery): treat it as a start request so the bubble
            // reappears rather than leaving a foreground notification with no window behind it.
            else -> startAssistant(startId)
        }
        return START_NOT_STICKY
    }

    private fun startAssistant(startId: Int) {
        notifications.ensureChannels()
        // startForeground MUST be the first thing after a startForegroundService() call: the
        // platform enforces a hard deadline (ForegroundServiceDidNotStartInTimeException), and any
        // check before it risks missing that deadline. The permission/EULA check therefore happens
        // AFTER the foreground state is established — the same order WatchdogService uses.
        // https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
        val started = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.FGS_ID_OVERLAY,
                overlayNotification.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(specialUse) failed", e)
            false
        }
        if (!started) {
            stopSelf(startId)
            return
        }
        if (!canRun()) {
            // The feature is off, the EULA is not accepted, or the grant was revoked while we were
            // dead. Never fake a bubble we are not allowed to show — and clear a stale ON flag so
            // the Settings toggle reflects what is actually on screen (BUG-030 honesty).
            Log.i(TAG, "overlay assistant not permitted to run — stopping")
            store.setEnabled(false)
            stopSelf(startId)
            return
        }
        bubble.show()
        Log.i(TAG, "overlay assistant running")
    }

    private fun canRun(): Boolean = OverlayAssistantPolicy.canShowBubble(
        enabled = store.isEnabled(),
        eulaAccepted = licenseStore.accepted(LicenseStore.LICENSE_VERSION),
        hasOverlayPermission = Settings.canDrawOverlays(this),
    )

    // ---------------------------------------------------------------------------------------------
    // Quick-ask panel
    // ---------------------------------------------------------------------------------------------

    private fun openPanel() {
        quickAsk.value = OverlayQuickAsk.expand(quickAsk.value)
        if (panel.isAdded) return
        // The screen is NOT read here: it is read lazily, and only for a request the pure
        // `OverlayRequestClassifier` classifies as being about the screen (see `OverlayAgentHandoff`
        // / `OverlayScreenContextReader.readBlock`). An unrelated request must never touch the screen.
        panel.open()
    }

    /**
     * Hides the quick-ask panel by removing its view, but — crucially — without destroying the
     * [OverlayComposeHost], its `LifecycleOwner` or the composition (see [OverlayPanelController]).
     */
    private fun hidePanel() {
        quickAsk.value = OverlayQuickAsk.collapse()
        panel.remove()
        // The panel and the bubble share the accessibility-overlay token; make sure the bubble is
        // still attached after the panel window goes away.
        bubble.reassert()
    }

    private fun closeAssistant() {
        hidePanel()
        store.setEnabled(false)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------------
    // Hand-off to the existing agent entry point
    // ---------------------------------------------------------------------------------------------

    /**
     * Sends the draft to the agent: the target chat (the persistent «Распознавание экрана» chat for
     * an on-screen question, a fresh chat otherwise) is ensured/created and the agent turn is started
     * from here, through the same [com.andharness.ru.domain.agent.AgentOrchestrator.run] entry point
     * scheduled tasks use, so the hand-off does NOT depend on an Activity being visible (BUG-041);
     * see [OverlayAgentHandoff].
     */
    private fun submit() {
        val state = quickAsk.value
        val text = OverlayQuickAsk.payload(state) ?: return
        if (!OverlayQuickAsk.canSubmit(state)) return
        quickAsk.value = OverlayQuickAsk.sending(state)
        scope.launch {
            try {
                val chatId = handoff.startTurn(text)
                handoff.openChat(chatId)
                // Only now, once the chat exists and the turn has been started, is it safe to close
                // the panel. Any earlier and a failure would leave the user with neither the panel
                // nor a delivered question.
                quickAsk.value = OverlayQuickAsk.sent()
                hidePanel()
            } catch (e: Exception) {
                Log.e(TAG, "hand-off to the agent failed", e)
                quickAsk.value = OverlayQuickAsk.failed(
                    quickAsk.value,
                    "Не удалось отправить: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / display change: re-apply the clamp so a bubble placed near the old right edge
        // cannot end up outside the new, possibly narrower, screen. move also repaints it.
        if (bubble.isAdded) bubble.move(0f, 0f)
        // A display change can also drop/re-create overlay windows; make sure the bubble survives.
        bubble.reassert()
    }

    override fun onDestroy() {
        // Teardown order matters (BUG-029): detach the windows synchronously FIRST, then dispose the
        // hosts. `removeViewImmediate` guarantees no queued traversal can measure the ComposeView
        // after `destroy()` has cleared its view-tree owners. Normal `hidePanel()` intentionally does
        // not destroy anything — see its KDoc.
        panel.remove(immediate = true)
        bubble.remove(immediate = true)
        panel.destroy()
        bubble.destroy()
        scope.cancel()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayAssistant"

        const val ACTION_START = "com.andharness.ru.action.OVERLAY_ASSISTANT_START"
        const val ACTION_STOP = "com.andharness.ru.action.OVERLAY_ASSISTANT_STOP"

        /**
         * Default title of the fresh chat created for a request that is NOT about the screen. The
         * persistent on-screen chat uses «Распознавание экрана» instead
         * ([com.andharness.ru.domain.assist.overlay.OverlayChatRouting.SCREEN_TITLE]).
         */
        const val CHAT_TITLE = "Плавающий помощник"

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayAssistantService::class.java).setAction(ACTION_START),
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed", e)
            false
        }

        /**
         * Stops the bubble. Uses [Context.stopService] (not an `ACTION_STOP` command) so it is safe
         * from the background and cannot spin up a fresh instance.
         * https://developer.android.com/develop/background-work/services#Stopping
         */
        fun stop(context: Context): Boolean = try {
            context.stopService(Intent(context, OverlayAssistantService::class.java))
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            false
        }

        /**
         * BUG-030 reconciliation, shared by [com.andharness.ru.AndharnessApplication] (cold process
         * start) and [com.andharness.ru.MainActivity] (app brought to the foreground while a process
         * without the service is still alive). The persisted switch can outlive the service, so the
         * real state is re-derived from the same [OverlayAssistantPolicy.canShowBubble] gate the
         * service itself enforces:
         *  - every approval holds → (re)start the service idempotently;
         *  - a gate is missing → clear the stale flag so the toggle cannot claim ON.
         *
         * Returns true when the on-disk switch now matches reality.
         */
        fun reconcile(
            context: Context,
            store: OverlayAssistantStore,
            licenseStore: LicenseStore,
        ): Boolean = when (
            OverlayAssistantPolicy.decideStartup(
                enabled = store.isEnabled(),
                eulaAccepted = licenseStore.accepted(LicenseStore.LICENSE_VERSION),
                hasOverlayPermission = Settings.canDrawOverlays(context),
            )
        ) {
            OverlayAssistantPolicy.StartupDecision.StartService -> start(context)
            OverlayAssistantPolicy.StartupDecision.DisableFlag -> {
                Log.i(TAG, "Clearing a stale overlay assistant flag")
                store.setEnabled(false)
                true
            }
            OverlayAssistantPolicy.StartupDecision.NoAction -> true
        }
    }
}
