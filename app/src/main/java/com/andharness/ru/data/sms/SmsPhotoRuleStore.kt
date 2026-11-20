package com.andharness.ru.data.sms

import com.andharness.ru.domain.sms.SmsPhotoCooldown
import com.andharness.ru.domain.sms.SmsPhotoRule
import com.andharness.ru.domain.sms.SmsPhotoRuleCodec
import com.andharness.ru.domain.sms.SmsPhotoRuleEngine
import com.andharness.ru.domain.sms.SmsPhotoRuleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persistent config for the trusted-SMS photo report.
 *
 * Backed by a single JSON file `<rootDir>/sms_photo_rule.json` written atomically (temp file +
 * rename). Like [com.andharness.ru.data.calls.CallRulesStore] and
 * [com.andharness.ru.data.notifications.NotificationRulesStore] it is deliberately **Android-free** —
 * it only takes a directory [File] — so the whole store (CRUD, bounds, cooldown log, corrupt-file
 * degradation) is exercised on the JVM; the DI module supplies `sms_photo_rule` under
 * `context.filesDir`.
 *
 * The default state is the opt-in no-op: [SmsPhotoRule.enabled] is `false`, so an empty/missing file
 * changes nothing.
 */
class SmsPhotoRuleStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _state = MutableStateFlow(load())

    private val _rule = MutableStateFlow(_state.value.rule)

    /** The whole persisted state (rule + cooldown log) as an observable value. */
    val state: StateFlow<SmsPhotoRuleState> = _state.asStateFlow()

    /** The current rule as an observable value. Defaults to the opt-in no-op. */
    val rule: StateFlow<SmsPhotoRule> = _rule.asStateFlow()

    /** Current rule, never throws. */
    @Synchronized
    fun current(): SmsPhotoRule = _state.value.rule

    /** Applies [transform] to the current rule and persists the sanitized result. */
    @Synchronized
    fun update(transform: (SmsPhotoRule) -> SmsPhotoRule) {
        persist(_state.value.copy(rule = transform(_state.value.rule)))
    }

    /** Master opt-in switch (`false` = nothing is captured or replied to). */
    @Synchronized
    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    /** Sets the reply prefix (trimmed and bounded by the codec). */
    @Synchronized
    fun setReplyTextPrefix(prefix: String) = update { it.copy(replyTextPrefix = prefix) }

    /** Sets the per-number cooldown in seconds (bounded by the codec; `0` disables it). */
    @Synchronized
    fun setCooldownSeconds(seconds: Int) = update { it.copy(cooldownSeconds = seconds) }

    /** Epoch-millis of the last automatic photo reply to [number], or `null` when never replied to. */
    @Synchronized
    fun lastReplyAt(number: String): Long? {
        val normalized = SmsPhotoRuleEngine.normalizeNumber(number) ?: return null
        return _state.value.replyLog[normalized]
    }

    /** `true` while [number] is inside its per-number cooldown (using the current rule's setting). */
    @Synchronized
    fun isCoolingDown(number: String, nowMs: Long): Boolean =
        SmsPhotoCooldown.isCoolingDown(
            lastAtMs = lastReplyAt(number),
            nowMs = nowMs,
            cooldownSeconds = _state.value.rule.cooldownSeconds,
        )

    /**
     * Records that an automatic photo reply was just sent to [number] at [atMs]. The log is bounded
     * by [SmsPhotoRuleCodec.MAX_REPLY_LOG] (most recent kept) so it can never grow without limit.
     */
    @Synchronized
    fun noteReply(number: String, atMs: Long = System.currentTimeMillis()) {
        val normalized = SmsPhotoRuleEngine.normalizeNumber(number) ?: return
        persist(_state.value.copy(replyLog = _state.value.replyLog + (normalized to atMs)))
    }

    /** Clears the cooldown log. Returns `true` only when there was something to clear. */
    @Synchronized
    fun clearReplyLog(): Boolean {
        if (_state.value.replyLog.isEmpty()) return false
        persist(_state.value.copy(replyLog = emptyMap()))
        return true
    }

    private fun load(): SmsPhotoRuleState = try {
        if (file.isFile) SmsPhotoRuleCodec.decode(file.readText()) else SmsPhotoRuleState()
    } catch (_: Exception) {
        SmsPhotoRuleState()
    }

    private fun persist(next: SmsPhotoRuleState) {
        try {
            val bounded = SmsPhotoRuleCodec.sanitize(next)
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            val encoded = SmsPhotoRuleCodec.encode(bounded)
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                file.writeText(encoded)
                temp.delete()
            }
            _state.value = bounded
            _rule.value = bounded.rule
        } catch (_: Exception) {
            // A failed save leaves the in-memory state unchanged (visible failure, no fake success).
        }
    }

    companion object {
        const val FILE_NAME = "sms_photo_rule.json"

        /** DI directory name under `context.filesDir`. */
        const val DIR_NAME = "sms_photo_rule"
    }
}
