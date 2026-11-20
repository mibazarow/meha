package com.andharness.ru.domain.person

import java.util.Locale

/** One installed application as seen by the index-config tools: package id + display label. */
data class InstalledApp(
    val pkg: String,
    val label: String,
)

/**
 * Pure, JVM-testable resolution of an installed app by a user-typed name or package.
 *
 * Matching order (case-insensitive), first non-empty stage wins:
 * 1. exact package id;
 * 2. exact display label;
 * 3. display label contains the query.
 *
 * Results preserve the input order and are de-duplicated by package, so the caller can treat a
 * single hit as a safe pick and anything else as ambiguous (never guess).
 */
object InstalledAppResolver {

    fun resolve(query: String, apps: List<InstalledApp>): List<InstalledApp> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val exactPackage = apps.filter { it.pkg.equals(q, ignoreCase = true) }
        if (exactPackage.isNotEmpty()) return distinctByPackage(exactPackage)
        val exactLabel = apps.filter { it.label.equals(q, ignoreCase = true) }
        if (exactLabel.isNotEmpty()) return distinctByPackage(exactLabel)
        val contains = apps.filter { it.label.contains(q, ignoreCase = true) }
        return distinctByPackage(contains)
    }

    private fun distinctByPackage(apps: List<InstalledApp>): List<InstalledApp> {
        val seen = HashSet<String>()
        return apps.filter { seen.add(it.pkg.lowercase(Locale.US)) }
    }
}
