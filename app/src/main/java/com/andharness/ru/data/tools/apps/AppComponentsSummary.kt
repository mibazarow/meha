package com.andharness.ru.data.tools.apps

/**
 * One declared application component as read from `PackageManager` for `app_services` /
 * `app_receivers`. Deliberately free of `android.content.pm` types so the summary logic can be
 * unit-tested on the JVM.
 *
 * `free` = the process is only reported when the manifest declares one
 * (`ComponentInfo.processName`); a `null` means the component runs in the app's default process.
 *
 * Reference: `PackageInfo.services` / `PackageInfo.receivers`,
 * `ComponentInfo.exported` / `ComponentInfo.processName`:
 * https://developer.android.com/reference/android/content/pm/PackageInfo
 * https://developer.android.com/reference/android/content/pm/ComponentInfo
 */
internal data class DeclaredComponent(
    val name: String,
    val exported: Boolean,
    val process: String?,
)

/**
 * Pure, JVM-testable formatting of the `app_services` / `app_receivers` result text: one line per
 * component with its exported flag and (when the manifest sets it) its process, capped to
 * [MAX_LISTED] entries with an honest "… и ещё N" tail.
 */
internal object AppComponentsSummary {

    /** Matches `AppToolLimits.MAX_COMPONENTS`; kept local so the object stays Android-free. */
    const val MAX_LISTED = 60

    fun format(label: String, components: List<DeclaredComponent>): String {
        if (components.isEmpty()) return "$label: нет"
        val shown = components.take(MAX_LISTED)
        val body = shown.joinToString("\n") { component ->
            val exported = if (component.exported) "exported" else "not exported"
            val process = component.process
                ?.takeIf { it.isNotBlank() }
                ?.let { ", процесс: $it" }
                .orEmpty()
            "  ${component.name} [$exported$process]"
        }
        val suffix = if (components.size > MAX_LISTED) {
            "\n  … и ещё ${components.size - MAX_LISTED}"
        } else {
            ""
        }
        return "$label (${components.size}):\n$body$suffix"
    }
}
