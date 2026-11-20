package com.andharness.ru.data.insights

import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.domain.agent.ToolLabels
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.insights.Insight
import com.andharness.ru.domain.insights.InsightAnalyzer
import com.andharness.ru.domain.insights.InsightKind
import com.andharness.ru.domain.insights.InsightsConfig
import com.andharness.ru.domain.notify.NotificationCaptureLog
import java.time.ZoneId

/**
 * Reads the app's own local stores and hands them to the pure [InsightAnalyzer].
 *
 * The three sources are exactly the ones the task allows:
 * - [ToolAuditRepository.all] — the append-only tool-call journal (bounded by its retention);
 * - [SkillStore.list] — the app-skill library with `lastUsedAt`/`createdAt`;
 * - [NotificationCaptureLog.recent] — the bounded, in-memory captured-notification buffer.
 *
 * There is deliberately **no** battery-log or storage-history source wired here: the app only reads
 * an instantaneous battery percentage
 * (`BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` —
 * https://developer.android.com/reference/android/os/BatteryManager#BATTERY_PROPERTY_CAPACITY) and a
 * current `StatFs` snapshot, and samples neither over time. A "trend" from a single point would be
 * fabricated, so the section is simply absent. When a sampler is added, a new builder can be plugged
 * into [InsightAnalyzer.build] the same way.
 *
 * This class is Android-free (it depends only on store interfaces/`java.time`) and the analysis
 * itself lives in `domain/insights/`. Reading the audit table goes through
 * [ToolAuditRepository.all], which is the DAO-backed Room read documented at
 * https://developer.android.com/training/data-storage/room/accessing-data
 */
class InsightsAssembler(
    private val audit: ToolAuditRepository,
    private val skillStore: SkillStore,
    private val captureLog: NotificationCaptureLog,
) {

    /**
     * Collects the current insights. Store failures are NOT swallowed: the caller (the
     * `insights_list` handler) reports an honest error instead of an empty list, because an empty
     * list would falsely claim "there is not enough data".
     */
    suspend fun collect(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        config: InsightsConfig = InsightsConfig(),
        kinds: Set<InsightKind> = emptySet(),
        limit: Int = config.maxInsights,
    ): List<Insight> = InsightAnalyzer.build(
        audit = audit.all(),
        skills = skillStore.list(),
        notifications = captureLog.recent(limit = NotificationCaptureLog.MAX_ENTRIES),
        nowMillis = nowMillis,
        zone = zone,
        config = config,
        kinds = kinds,
        limit = limit,
        toolLabel = ToolLabels::label,
    )
}
