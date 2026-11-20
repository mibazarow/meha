package com.andharness.ru.ui.settings

import android.content.Context
import android.provider.Settings
import com.andharness.ru.data.assist.OverlayAssistantStore
import com.andharness.ru.domain.assist.overlay.OverlayAssistantPolicy
import com.andharness.ru.service.OverlayAssistantService

/**
 * Handles the «Плавающий помощник» (floating overlay) section: the honest toggle decision delegated
 * to [OverlayAssistantPolicy], the one-shot system-permission prompt and the on-resume re-read of
 * `Settings.canDrawOverlays`. State is written into the shared [SettingsStateHolder].
 */
class OverlayAssistantController(
    private val state: SettingsStateHolder,
    private val appContext: Context,
    private val overlayAssistantStore: OverlayAssistantStore,
) {

    /**
     * Тумблер «Плавающий помощник». Решение принимает чистый [OverlayAssistantPolicy]:
     * - выключение → сброс флага и остановка foreground-сервиса;
     * - включение при выданном `SYSTEM_ALERT_WINDOW` → флаг + запуск сервиса;
     * - включение без разрешения → флаг ОСТАЁТСЯ выключенным, показывается честное сообщение и
     *   запрашивается системный экран `ACTION_MANAGE_OVERLAY_PERMISSION` (one-shot через
     *   [SettingsUiState.overlayPermissionPromptNonce]). Фейкового «включено» не бывает.
     */
    fun onOverlayAssistantEnabledChange(value: Boolean) {
        when (OverlayAssistantPolicy.decideToggle(value, Settings.canDrawOverlays(appContext))) {
            OverlayAssistantPolicy.ToggleDecision.Disable -> {
                overlayAssistantStore.setEnabled(false)
                OverlayAssistantService.stop(appContext)
                state.update {
                    it.copy(overlayAssistantEnabled = false, overlayPermissionMessage = null)
                }
            }
            OverlayAssistantPolicy.ToggleDecision.Enable -> {
                overlayAssistantStore.setEnabled(true)
                OverlayAssistantService.start(appContext)
                state.update {
                    it.copy(
                        overlayAssistantEnabled = true,
                        overlayAssistantPermissionGranted = true,
                        overlayPermissionMessage = null,
                    )
                }
            }
            OverlayAssistantPolicy.ToggleDecision.RequestPermission -> {
                overlayAssistantStore.setEnabled(false)
                state.update {
                    it.copy(
                        overlayAssistantEnabled = false,
                        overlayAssistantPermissionGranted = false,
                        overlayPermissionMessage = OVERLAY_PERMISSION_MESSAGE,
                        overlayPermissionPromptNonce =
                            System.currentTimeMillis().coerceAtLeast(1L),
                    )
                }
            }
        }
    }

    /** The screen has opened the system overlay screen; clear the one-shot request. */
    fun onOverlayPermissionPromptShown() {
        state.update { it.copy(overlayPermissionPromptNonce = 0L) }
    }

    /**
     * Re-reads the OS grant. Called when the settings screen resumes, so a permission the user just
     * granted in system Settings is reflected without needing a second toggle flip.
     */
    fun refreshOverlayPermission() {
        val granted = Settings.canDrawOverlays(appContext)
        state.update { it.copy(overlayAssistantPermissionGranted = granted) }
    }

    companion object {
        /**
         * Honest explanation shown when the overlay grant is missing: nothing is enabled, the user is
         * sent to the system list and told to come back.
         */
        const val OVERLAY_PERMISSION_MESSAGE =
            "Нужно разрешение «Поверх других приложений». Открываю системный список — найдите " +
                "«Меха» и включите его, затем вернитесь и включите переключатель снова."
    }
}
