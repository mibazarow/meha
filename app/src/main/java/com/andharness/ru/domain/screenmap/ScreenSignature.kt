package com.andharness.ru.domain.screenmap

/**
 * Stable, pure screen-signature rule for the per-app screen map.
 *
 * A screen is identified by the foreground Activity's short class name plus a short hash of the top
 * [TOP_LABELS] node labels. Labels are normalised (trimmed + lower-cased) and taken in reading order,
 * so the same logical screen keeps the same signature across visits while volatile, order-stable
 * content (a screen with the same controls) does not produce a new entry every time. When the
 * Activity class is unavailable the signature still works from the labels alone.
 *
 * Android-free by construction: the caller resolves `AccessibilityNodeInfo.getClassName()` /
 * `getPackageName()` and passes plain strings in.
 *
 * References (official Android docs):
 * - `AccessibilityNodeInfo.getClassName()` (the node's class name, e.g. the Activity's root view):
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getClassName()
 */
object ScreenSignature {

    /** How many leading node labels participate in the signature hash. */
    const val TOP_LABELS = 8

    /** Placeholder class name when neither the Activity nor the root node class is known. */
    const val UNKNOWN = "unknown"

    /**
     * Signature of a screen: `<shortClassName>#<8-hex-digit hash>`. [labels] are the node labels in
     * reading order; only the first [TOP_LABELS] non-blank ones matter.
     */
    fun of(activityClass: String?, labels: List<String>): String {
        val short = shortClassName(activityClass)
        val top = labels.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .take(TOP_LABELS)
            .toList()
        val basis = short + "\u001f" + top.joinToString("\u001e")
        return "$short#${fnv1a(basis)}"
    }

    /** Short class name (after the last `.`), or [UNKNOWN] for a blank/absent value. */
    fun shortClassName(activityClass: String?): String =
        activityClass?.trim()?.takeIf { it.isNotBlank() }?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            ?: UNKNOWN

    /**
     * FNV-1a 32-bit hash rendered as 8 lower-case hex digits. Deterministic across JVM versions and
     * Android (unlike `String.hashCode`, which is also fine, FNV-1a spreads short label strings
     * better), so a signature written on-device stays valid after an update.
     */
    private fun fnv1a(value: String): String {
        var hash = -0x7ee3623b // 2166136261
        for (ch in value) {
            hash = hash xor ch.code
            hash *= 0x01000193 // 16777619
        }
        return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    }
}
