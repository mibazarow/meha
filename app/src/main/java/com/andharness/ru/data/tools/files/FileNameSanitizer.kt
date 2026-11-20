package com.andharness.ru.data.tools.files

/**
 * Strips directory components (`/`, `\`), NUL bytes and surrounding whitespace from a raw file name;
 * rejects `null`, `.` / `..` and anything that ends up empty. This is the single shared implementation
 * for the download (`NetworkToolsHandler`) and APK-install (`AppInstallApkPolicy`) file-name paths.
 * Deliberately distinct from the other, different sanitizers (`AttachmentStore.sanitizeName`,
 * `PdfOutputNames.slug`, archive entry sanitizers), which apply their own rules and are left untouched.
 *
 * Pure Kotlin / `java.lang.String` — unit-testable on the JVM.
 * References:
 * - `String.substringAfterLast` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/substring-after-last.html
 * - `String.trim()` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/trim.html
 */
fun sanitizeFileName(raw: String?): String? {
    if (raw == null || raw.contains('\u0000')) return null
    val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    return name
}
