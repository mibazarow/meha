package com.andharness.ru.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.andharness.ru.domain.usage.UsageEventKind
import com.andharness.ru.domain.usage.UsagePatternEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads raw device-usage events for the predictive suggestion engine (update.md idea #16).
 *
 * `UsageStatsManager.queryEvents` needs the special "Usage access" (PACKAGE_USAGE_STATS) grant, which
 * is a system Settings toggle rather than a runtime permission, so [hasUsageAccess] checks the app-op
 * before anyone queries. Without the grant the reader returns nothing and the caller must surface the
 * honest "usage access required" state — it never fabricates data.
 *
 * Official references:
 * - `UsageStatsManager.queryEvents` / `UsageEvents`:
 *   https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryEvents(long,%20long)
 * - `UsageEvents.Event` type constants (MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND / SCREEN_NON_INTERACTIVE):
 *   https://developer.android.com/reference/android/app/usage/UsageEvents.Event
 * - Usage access via `AppOpsManager.OPSTR_GET_USAGE_STATS`:
 *   https://developer.android.com/reference/android/app/AppOpsManager#OPSTR_GET_USAGE_STATS
 */
@Singleton
class UsageStatsReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** True when the user granted the special usage-access app-op. */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Normalises the last [days] of usage events into foreground sessions and screen-off markers.
     *
     * A foreground session is emitted when its `MOVE_TO_BACKGROUND` (pause) arrives — with the real
     * duration — and any unpaired resume still open at the end of the window is emitted with
     * `endTime == startTime` (unknown duration) so an app that never released the foreground is not
     * lost. The number of consumed raw events is bounded by [maxEvents] regardless of how busy the
     * window was.
     */
    fun readRecentEvents(
        days: Int = DEFAULT_DAYS,
        maxEvents: Int = DEFAULT_MAX_EVENTS
    ): List<UsagePatternEvent> {
        val end = System.currentTimeMillis()
        val safeDays = days.coerceIn(1, MAX_DAYS)
        val start = end - TimeUnit.DAYS.toMillis(safeDays.toLong())
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = usm.queryEvents(start, end) ?: return emptyList()

        val out = ArrayList<UsagePatternEvent>()
        val openSessions = HashMap<String, Long>()
        val event = UsageEvents.Event()
        val limit = maxEvents.coerceIn(1, MAX_EVENTS_HARD_CAP)
        var consumed = 0

        // Bounded loop: at most `limit` consumed events, and a hard iteration guard so a corrupt
        // cursor can never spin forever.
        while (consumed < limit && consumed < MAX_EVENTS_HARD_CAP && events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            consumed++

            @Suppress("DEPRECATION")
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val pkg = event.packageName
                    if (!pkg.isNullOrEmpty()) openSessions[pkg] = event.timeStamp
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val pkg = event.packageName
                    val started = if (pkg != null) openSessions.remove(pkg) else null
                    if (started != null) {
                        out += UsagePatternEvent(
                            packageName = pkg!!,
                            startTime = started,
                            endTime = event.timeStamp,
                            kind = UsageEventKind.APP_FOREGROUND
                        )
                    }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> if (Build.VERSION.SDK_INT >= 28) {
                    out += UsagePatternEvent(
                        packageName = "",
                        startTime = event.timeStamp,
                        endTime = event.timeStamp,
                        kind = UsageEventKind.SCREEN_NON_INTERACTIVE
                    )
                }

                UsageEvents.Event.SCREEN_INTERACTIVE -> if (Build.VERSION.SDK_INT >= 28) {
                    out += UsagePatternEvent(
                        packageName = "",
                        startTime = event.timeStamp,
                        endTime = event.timeStamp,
                        kind = UsageEventKind.SCREEN_INTERACTIVE
                    )
                }

                else -> Unit
            }
        }

        // Sessions never closed inside the window: keep them, but with an unknown duration.
        for ((pkg, started) in openSessions) {
            if (out.size >= limit) break
            out += UsagePatternEvent(
                packageName = pkg,
                startTime = started,
                endTime = started,
                kind = UsageEventKind.APP_FOREGROUND
            )
        }
        return out
    }

    companion object {
        /** Two weeks of history — enough for weekly rhythm without scanning an unbounded window. */
        const val DEFAULT_DAYS = 14
        const val MAX_DAYS = 31
        const val DEFAULT_MAX_EVENTS = 20_000
        private const val MAX_EVENTS_HARD_CAP = 50_000
    }
}
