package com.andharness.ru.data.notifications

import com.andharness.ru.domain.notifications.AppRule
import com.andharness.ru.domain.notifications.NotificationRules
import com.andharness.ru.domain.notifications.NotificationRulesCodec
import com.andharness.ru.domain.notifications.QuietHours
import com.andharness.ru.domain.notifications.RuleImportance
import com.andharness.ru.domain.notifications.SummaryMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persistent user-defined notification rules (checklist §1, «Умный инбокс уведомлений»): per-app
 * importance, quiet hours and the digest summary mode.
 *
 * Backed by a single JSON file `<rootDir>/notification_rules.json` written atomically (temp file +
 * rename). Like [com.andharness.ru.data.skill.SkillStore] it is deliberately **Android-free** — it
 * only takes a directory [File] — so the whole store (CRUD, bounds, corrupt-file degradation) is
 * exercised on the JVM; the DI module supplies `notification_rules` under `context.filesDir`.
 *
 * Every mutation goes through [NotificationRulesCodec.sanitize], so the stored set is always bounded
 * ([NotificationRulesCodec.MAX_APPS]) and a hand-edited file can never break the triage. All access is
 * `@Synchronized`; a failed disk write leaves the in-memory state unchanged (a failed save is visible
 * rather than silently reported as success).
 */
class NotificationRulesStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _rules = MutableStateFlow(load())

    /** Current rule set as an observable value. Defaults are a strict no-op. */
    val rules: StateFlow<NotificationRules> = _rules.asStateFlow()

    /** Current rule set, never throws. */
    @Synchronized
    fun current(): NotificationRules = _rules.value

    /**
     * Adds or replaces the rule for [appPackage] and returns the stored rule, or `null` for a blank
     * package (an anonymous rule is never saved). [note] is clamped by the codec.
     */
    @Synchronized
    fun setAppRule(
        appPackage: String,
        importance: RuleImportance,
        note: String? = null,
    ): AppRule? {
        val pkg = appPackage.trim()
        if (pkg.isEmpty()) return null
        val rule = AppRule(appPackage = pkg, importance = importance, note = note)
        val current = _rules.value
        // Replace any case-insensitive pre-existing entry so a re-save never leaves a stale duplicate.
        val withoutOld = current.apps.filterKeys { !it.equals(pkg, ignoreCase = true) }
        persist(current.copy(apps = withoutOld + (pkg to rule)))
        return rule
    }

    /**
     * Rule for [appPackage] (exact then case-insensitive), or `null`.
     */
    @Synchronized
    fun getAppRule(appPackage: String): AppRule? {
        val target = appPackage.trim()
        if (target.isEmpty()) return null
        val apps = _rules.value.apps
        return apps[target] ?: apps.entries.firstOrNull { it.key.equals(target, ignoreCase = true) }?.value
    }

    /**
     * Removes one per-app rule (case-insensitive). Returns `true` only when something was removed.
     * A blank [appPackage] clears every per-app rule (the global settings are kept).
     */
    @Synchronized
    fun removeAppRule(appPackage: String?): Boolean {
        val target = appPackage?.trim().orEmpty()
        val current = _rules.value
        val next = if (target.isEmpty()) {
            current.copy(apps = emptyMap())
        } else {
            current.copy(apps = current.apps.filterKeys { !it.equals(target, ignoreCase = true) })
        }
        if (next.apps.size == current.apps.size) return false
        persist(next)
        return true
    }

    /** Sets (or replaces) the quiet-hours window. */
    @Synchronized
    fun setQuietHours(hours: QuietHours) {
        persist(_rules.value.copy(quietHours = hours))
    }

    /** Sets the quiet-hours window from wall-clock fields; each field is coerced into range. */
    @Synchronized
    fun setQuietHours(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        setQuietHours(QuietHours.of(startHour, startMinute, endHour, endMinute))
    }

    /** Disables quiet hours. Returns `true` only when a window was actually set. */
    @Synchronized
    fun clearQuietHours(): Boolean {
        if (_rules.value.quietHours == null) return false
        persist(_rules.value.copy(quietHours = null))
        return true
    }

    /** Whether important notifications may still be memorized during quiet hours. */
    @Synchronized
    fun setQuietAllowImportant(enabled: Boolean) {
        persist(_rules.value.copy(quietAllowImportant = enabled))
    }

    /** Sets how much the digest shows. */
    @Synchronized
    fun setSummaryMode(mode: SummaryMode) {
        persist(_rules.value.copy(summaryMode = mode))
    }

    fun appRuleCount(): Int = current().apps.size

    private fun load(): NotificationRules = try {
        if (file.isFile) NotificationRulesCodec.decode(file.readText()) else NotificationRules()
    } catch (_: Exception) {
        NotificationRules()
    }

    private fun persist(rules: NotificationRules) {
        try {
            val bounded = NotificationRulesCodec.sanitize(rules)
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            val encoded = NotificationRulesCodec.encode(bounded)
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(encoded)
                temp.delete()
            }
            _rules.value = bounded
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible
            // (the rules simply do not change) rather than silently reporting success.
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the rules file. */
        const val DIR_NAME = "notification_rules"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "notification_rules.json"
    }
}
