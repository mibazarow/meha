package com.andharness.ru.data.calls

import com.andharness.ru.domain.calls.AutoReplyCooldown
import com.andharness.ru.domain.calls.CallRuleEngine
import com.andharness.ru.domain.calls.CallRules
import com.andharness.ru.domain.calls.CallRulesCodec
import com.andharness.ru.domain.calls.CallRulesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persistent user-defined call/SMS auto-handling rules (checklist §1, «Авто-обработка звонков/SMS»).
 *
 * Backed by a single JSON file `<rootDir>/call_rules.json` written atomically (temp file + rename).
 * Like [com.andharness.ru.data.skill.SkillStore] and
 * [com.andharness.ru.data.notifications.NotificationRulesStore] it is deliberately **Android-free** —
 * it only takes a directory [File] — so the whole store (CRUD, bounds, cooldown log, corrupt-file
 * degradation) is exercised on the JVM; the DI module supplies `call_rules` under `context.filesDir`.
 *
 * Every mutation goes through [CallRulesCodec.sanitize], so the number lists and the auto-reply log are
 * always bounded and a hand-edited file can never break the screening path. All access is
 * `@Synchronized`; a failed disk write leaves the in-memory state unchanged (a failed save is visible
 * rather than silently reported as success). The default state is the opt-in no-op: [CallRules.enabled]
 * is `false`, so an empty/missing file changes nothing.
 */
class CallRulesStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _state = MutableStateFlow(load())

    private val _rules = MutableStateFlow(_state.value.rules)

    /** The whole persisted state (rules + cooldown log) as an observable value. */
    val state: StateFlow<CallRulesState> = _state.asStateFlow()

    /** The current rule set as an observable value. Defaults are the opt-in no-op. */
    val rules: StateFlow<CallRules> = _rules.asStateFlow()

    /** Current rule set, never throws. */
    @Synchronized
    fun current(): CallRules = _state.value.rules

    /** Applies [transform] to the current rules and persists the sanitized result. */
    @Synchronized
    fun update(transform: (CallRules) -> CallRules) {
        persist(_state.value.copy(rules = transform(_state.value.rules)))
    }

    /** Master opt-in switch (`false` = nothing is auto-handled). */
    @Synchronized
    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    /** Reject calls from numbers that do not resolve to a contact. */
    @Synchronized
    fun setBlockUnknown(blockUnknown: Boolean) = update { it.copy(blockUnknown = blockUnknown) }

    /** Replaces the blocked-number list (normalized and bounded by the codec). */
    @Synchronized
    fun setBlockedNumbers(numbers: List<String>) =
        update { it.copy(blockedNumbers = CallRulesCodec.boundedNumbers(numbers)) }

    /** Replaces the allowlist (normalized and bounded by the codec); it always passes. */
    @Synchronized
    fun setAllowNumbers(numbers: List<String>) =
        update { it.copy(allowNumbers = CallRulesCodec.boundedNumbers(numbers)) }

    /** Enables/disables the automatic SMS reply for unknown numbers. */
    @Synchronized
    fun setAutoReplySms(enabled: Boolean) = update { it.copy(autoReplySms = enabled) }

    /** Sets the auto-reply text (trimmed and bounded by the codec). */
    @Synchronized
    fun setAutoReplyText(text: String) = update { it.copy(autoReplyText = text) }

    /** Enables/disables the automatic SMS reply while already in a call. */
    @Synchronized
    fun setAutoReplyWhenBusy(enabled: Boolean) = update { it.copy(autoReplyWhenBusy = enabled) }

    /** Enables/disables the call-back reminder for rejected/missed calls from known contacts. */
    @Synchronized
    fun setCallBackReminder(enabled: Boolean) = update { it.copy(callBackReminder = enabled) }

    /** Epoch-millis of the last automatic reply to [number], or `null` when it was never replied to. */
    @Synchronized
    fun lastAutoReplyAt(number: String): Long? {
        val normalized = CallRuleEngine.normalizeNumber(number) ?: return null
        return _state.value.autoReplyLog[normalized]
    }

    /** `true` while [number] is inside its per-number auto-reply cooldown. */
    @Synchronized
    fun isAutoReplyCoolingDown(
        number: String,
        nowMs: Long,
        cooldownMs: Long = AutoReplyCooldown.DEFAULT_COOLDOWN_MS,
    ): Boolean = AutoReplyCooldown.isCoolingDown(lastAutoReplyAt(number), nowMs, cooldownMs)

    /**
     * Records that an automatic reply was just sent to [number] at [atMs]. The log is bounded by
     * [CallRulesCodec.MAX_REPLY_LOG] (most recent kept) so it can never grow without limit.
     */
    @Synchronized
    fun noteAutoReply(number: String, atMs: Long = System.currentTimeMillis()) {
        val normalized = CallRuleEngine.normalizeNumber(number) ?: return
        persist(
            _state.value.copy(
                autoReplyLog = _state.value.autoReplyLog + (normalized to atMs),
            ),
        )
    }

    /** Clears the cooldown log. Returns `true` only when there was something to clear. */
    @Synchronized
    fun clearAutoReplyLog(): Boolean {
        if (_state.value.autoReplyLog.isEmpty()) return false
        persist(_state.value.copy(autoReplyLog = emptyMap()))
        return true
    }

    private fun load(): CallRulesState = try {
        if (file.isFile) CallRulesCodec.decode(file.readText()) else CallRulesState()
    } catch (_: Exception) {
        CallRulesState()
    }

    private fun persist(next: CallRulesState) {
        try {
            val bounded = CallRulesCodec.sanitize(next)
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            val encoded = CallRulesCodec.encode(bounded)
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(encoded)
                temp.delete()
            }
            _state.value = bounded
            _rules.value = bounded.rules
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible
            // (the rules simply do not change) rather than silently reporting success.
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the rules file. */
        const val DIR_NAME = "call_rules"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "call_rules.json"
    }
}
