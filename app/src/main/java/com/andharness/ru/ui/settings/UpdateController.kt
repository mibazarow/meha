package com.andharness.ru.ui.settings

import android.content.Context
import android.content.Intent
import com.andharness.ru.data.update.AndroidInstallLauncher
import com.andharness.ru.data.update.InAppUpdater
import com.andharness.ru.data.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles the in-app updater section: manual check, APK download/verification, the unknown-sources
 * gate and launching the system installer. [InAppUpdater] owns the single updater [StateFlow]; this
 * controller only mirrors transient messages and keeps one in-flight action. State is written into
 * the shared [SettingsStateHolder].
 */
class UpdateController(
    private val state: SettingsStateHolder,
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val updater: InAppUpdater,
) {

    /** Single in-flight updater action (check or download); a second tap is ignored while active. */
    private var updateJob: Job? = null

    /**
     * Manual update check (`force = true`, i.e. always hits the network). Best-effort: failures are
     * surfaced through [SettingsUiState.updateStatusText] and never crash the screen.
     */
    fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            state.update { it.copy(updateMessage = null, updateMessageIsError = false) }
            val result = updater.check(force = true)
            if (result is UpdateState.UpToDate) {
                state.update {
                    it.copy(
                        updateMessage = "Установлена последняя версия.",
                        updateMessageIsError = false,
                    )
                }
            }
        }
    }

    /**
     * Downloads and verifies the offered APK. Progress is carried by [UpdateState.Downloading], so no
     * separate counter is kept here. A successful, signer-verified download is then either ready to
     * install or gated behind the unknown-sources permission (see [InAppUpdater.resolveInstall]).
     */
    fun downloadUpdate() {
        if (updater.state.value !is UpdateState.Available) return
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            state.update { it.copy(updateMessage = null, updateMessageIsError = false) }
            updater.download().fold(
                onSuccess = { file -> updater.resolveInstall(file) },
                onFailure = {
                    val message = (updater.state.value as? UpdateState.Error)?.message
                    state.update { it.copy(updateMessage = message, updateMessageIsError = true) }
                }
            )
        }
    }

    /**
     * Opens the system installer for the verified APK. The user still confirms the install in the
     * system UI — this only launches it.
     */
    fun installUpdate() {
        val ready = updater.state.value as? UpdateState.InstallReady ?: return
        val launched = updater.install(ready.file)
        updater.dismiss()
        state.update {
            it.copy(
                updateMessage = if (launched) {
                    "Установщик открыт — подтвердите установку."
                } else {
                    "Не удалось открыть установщик обновления."
                },
                updateMessageIsError = !launched,
            )
        }
    }

    /**
     * Unknown-sources gate: when the permission is already granted, re-runs the install resolution;
     * otherwise opens the system settings screen. The user returns and presses the button again,
     * which re-reads the gate.
     */
    fun requestUnknownSources() {
        val needs = updater.state.value as? UpdateState.NeedsUnknownSources ?: return
        if (AndroidInstallLauncher.canRequestPackageInstalls(appContext)) {
            updater.resolveInstall(needs.file)
            return
        }
        val launched = runCatching {
            appContext.startActivity(
                AndroidInstallLauncher.unknownSourcesIntent(appContext)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        state.update {
            it.copy(
                updateMessage = if (launched) {
                    "Разрешите установку и, вернувшись, нажмите «Разрешить» ещё раз."
                } else {
                    "Не удалось открыть настройки разрешений."
                },
                updateMessageIsError = !launched,
            )
        }
    }

    /** Dismisses the current updater dialog and returns the manager to [UpdateState.Idle]. */
    fun dismissUpdate() {
        updater.dismiss()
        state.update { it.copy(updateMessage = null, updateMessageIsError = false) }
    }
}
