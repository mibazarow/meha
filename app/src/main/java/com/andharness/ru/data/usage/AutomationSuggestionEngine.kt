package com.andharness.ru.data.usage

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.andharness.ru.domain.automation.AutomationRule
import com.andharness.ru.domain.automation.AutomationRuleRepository
import com.andharness.ru.domain.automation.AutomationScheduler
import com.andharness.ru.domain.automation.AutomationSpec
import com.andharness.ru.domain.usage.AutomationSuggestion
import com.andharness.ru.domain.usage.SuggestionDecisions
import com.andharness.ru.domain.usage.UsageAnalysisConfig
import com.andharness.ru.domain.usage.UsageEventKind
import com.andharness.ru.domain.usage.UsagePatternAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ties the predictive-suggestion pipeline together (update.md idea #16):
 * read usage events → mine patterns → drop already-accepted/dismissed ones → optionally turn one
 * into a real automation rule through the SAME repository + scheduler the
 * `automation_rule_create` tool uses.
 *
 * Shared by the `automation_suggestions` tool and the in-app «Подсказки» screen so both surfaces
 * behave identically. Honest by construction: without usage access it returns nothing and
 * [hasUsageAccess] lets callers say so; system/non-launchable packages are filtered out so the
 * suggestions are about apps the user actually opens.
 */
@Singleton
class AutomationSuggestionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: UsageStatsReader,
    private val decisions: SuggestionDecisionStore,
    private val ruleRepository: AutomationRuleRepository,
    private val scheduler: AutomationScheduler
) {

    fun hasUsageAccess(): Boolean = reader.hasUsageAccess()

    /** True when a suggestion id was already accepted or dismissed by the user. */
    fun isHandled(id: String): Boolean = decisions.isHandled(id)

    /** True only for a debug build — gates the debug-only simulation tool. */
    fun isDebuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** The suggestions still worth showing, best first, already filtered against past decisions. */
    suspend fun currentSuggestions(limit: Int = DEFAULT_LIMIT): List<AutomationSuggestion> =
        withContext(Dispatchers.IO) {
            if (!reader.hasUsageAccess()) return@withContext emptyList()
            val events = reader.readRecentEvents()
                .filter { it.kind != UsageEventKind.APP_FOREGROUND || isLaunchable(it.packageName) }
            if (events.isEmpty()) return@withContext emptyList()

            UsagePatternAnalyzer.analyze(
                events = events,
                config = UsageAnalysisConfig(excludedPackages = excludedPackages()),
                appLabel = ::appLabel
            )
                .let { SuggestionDecisions.visible(it, decisions.acceptedIds() + decisions.dismissedIds()) }
                .take(limit.coerceIn(1, MAX_LIMIT))
        }

    /**
     * Creates the suggested rule (trigger `time` + action `notify`), arms its WorkManager work and
     * records the suggestion as accepted so it never comes back. Returns the new rule id.
     *
     * The rule goes through the same canonical [AutomationSpec.validateRule] as
     * `automation_rule_create`: an invalid suggestion is refused honestly by throwing
     * [IllegalArgumentException] with the Russian validation reason, and nothing is created.
     */
    suspend fun accept(suggestion: AutomationSuggestion): Long = withContext(Dispatchers.IO) {
        val triggerType = AutomationSpec.normalizeTrigger(suggestion.triggerType) ?: suggestion.triggerType
        val actionType = AutomationSpec.normalizeAction(suggestion.actionType) ?: suggestion.actionType
        AutomationSpec.validateRule(triggerType, suggestion.triggerConfig, actionType, suggestion.actionConfig)
            ?.let { reason -> throw IllegalArgumentException(reason) }

        val id = ruleRepository.create(
            AutomationRule(
                id = 0,
                name = suggestion.title,
                enabled = true,
                triggerType = triggerType,
                triggerConfig = suggestion.triggerConfig,
                actionType = actionType,
                actionConfig = suggestion.actionConfig
            )
        )
        ruleRepository.getById(id)?.let { scheduler.schedule(it) }
        decisions.markAccepted(suggestion.id)
        id
    }

    /** Hides the suggestion for good. */
    fun dismiss(suggestion: AutomationSuggestion) = decisions.markDismissed(suggestion.id)

    /** Packages that are pure system noise and can never be "opened by the user". */
    private fun excludedPackages(): Set<String> = setOf(
        context.packageName,
        "android",
        "com.android.systemui"
    )

    private fun isLaunchable(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()
    }

    private fun appLabel(pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 20
    }
}
