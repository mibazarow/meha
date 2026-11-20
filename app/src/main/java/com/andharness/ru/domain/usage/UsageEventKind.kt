package com.andharness.ru.domain.usage


/**
 * Device-usage mining for predictive automation suggestions (update.md idea #16).
 *
 * This file is intentionally **pure Kotlin** (no Android imports, only `java.time`, available since
 * API 26) so the clustering logic can be unit-tested on the JVM. The Android layer
 * (`data/usage/UsageStatsReader`) produces [UsagePatternEvent]s from `UsageStatsManager` and feeds
 * them here.
 *
 * Design goals, mirroring the task:
 * - cluster foreground-app events by **hour of day** (and count distinct days), plus an optional
 *   **quiet-night** heuristic derived from screen-off events;
 * - stay conservative: a pattern must repeat on several days (`minDays`), appear often enough
 *   (`minOccurrences`) and clear a confidence floor (`minConfidence`);
 * - never guess: an empty/insufficient history yields an empty list, not a fabricated suggestion.
 */

/** What a raw [UsagePatternEvent] represents. */
enum class UsageEventKind { APP_FOREGROUND, SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE, OTHER }
