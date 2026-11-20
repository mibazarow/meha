package com.andharness.ru.ui.chats

import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.permission.PermissionManager
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the screen's bridge to the running agent for approvals: cancelling a run, granting/denying a
 * permission the tool asked for, re-checking a special-access grant after the user returns from
 * system settings, and submitting/cancelling the results of [UiInteractionBridge] requests.
 */
internal class ChatToolApprovalController(
    private val chatId: Long,
    private val scope: CoroutineScope,
    private val orchestrator: AgentOrchestrator,
    private val permissionManager: PermissionManager,
    private val uiBridge: UiInteractionBridge
) {

    fun stopGeneration() = orchestrator.cancel(chatId)

    fun grantPermission() = orchestrator.grantPermission(chatId)

    fun denyPermission() = orchestrator.denyPermission(chatId)

    fun isPermissionGranted(permission: String): Boolean = permissionManager.isGranted(permission)

    /**
     * §8: after the user returns from a system settings screen, services like accessibility /
     * notification-listener connect slightly after the Activity resumes, so re-check the grant a
     * few times before answering the agent.
     *
     * Runs in [scope] on purpose: the previous implementation re-checked on a
     * `rememberCoroutineScope`, which is cancelled as soon as the user leaves this screen — the
     * grant/deny was then never submitted and the agent blocked until its 60 s gate timeout. The
     * ViewModel outlives the composition, so the result is always delivered.
     */
    fun awaitPermissionResult(permission: String) {
        scope.launch {
            var granted = permissionManager.isGranted(permission)
            var attempts = 0
            while (!granted && attempts < 6) {
                delay(400)
                granted = permissionManager.isGranted(permission)
                attempts++
            }
            if (granted) grantPermission() else denyPermission()
        }
    }

    fun isSpecialPermission(permission: String): Boolean = SpecialAccess.isSpecial(permission)

    fun submitUiResult(result: UiResult) = uiBridge.submit(result)

    fun cancelUiRequest() = uiBridge.cancel()
}
