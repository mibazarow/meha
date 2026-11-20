package com.andharness.ru.data.tools.media

/**
 * Pure, dependency-free bits shared by the `media_open` / `media_share` handlers and the
 * `device_search` result formatter. Kept free of `android.*` types so the decision logic
 * (MIME fallback, URI classification, MediaStore id extraction) is unit-testable on the JVM.
 *
 * The Android glue itself (building `Intent`s, `ContentResolver.getType`) lives in
 * [MediaActionToolsHandler]; this object only supplies the strings/decisions it needs.
 */
internal object MediaActionPlanner {

    /** Type used when neither the caller nor the provider can tell us the MIME type. */
    const val FALLBACK_MIME = "*/*"

    /**
     * A URI scheme prefix (`scheme://…`). RFC 3986 scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ).
     * https://developer.android.com/reference/android/net/Uri#parse(java.lang.String)
     */
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * MIME resolution order: an explicit caller value wins, then the provider/extension-detected
     * type, else [FALLBACK_MIME]. Blank/whitespace inputs are treated as "not provided", so the
     * model can safely omit `mime`.
     */
    fun resolveMime(explicit: String?, detected: String?): String {
        val explicitTrimmed = explicit?.trim().orEmpty()
        if (explicitTrimmed.isNotEmpty()) return explicitTrimmed
        val detectedTrimmed = detected?.trim().orEmpty()
        if (detectedTrimmed.isNotEmpty()) return detectedTrimmed
        return FALLBACK_MIME
    }

    /** True when [raw] already carries a URI scheme (`content://`, `file://`, `http://`, …). */
    fun hasScheme(raw: String): Boolean = SCHEME.containsMatchIn(raw.trim())

    /** True for a MediaStore/other-provider `content://` URI. */
    fun isContentUri(raw: String): Boolean = raw.trim().startsWith("content://", ignoreCase = true)

    /**
     * The MediaStore row id from a URI like `content://media/external/images/media/423` → `423`,
     * or `null` when [uri] is not a `content://` URI or has no numeric trailing segment. Used to
     * surface the id of an image hit in `device_search`.
     */
    fun mediaStoreId(uri: String): Long? {
        val trimmed = uri.trim()
        if (!isContentUri(trimmed)) return null
        val withoutQuery = trimmed.substringBefore('?').trimEnd('/')
        return withoutQuery.substringAfterLast('/').toLongOrNull()
    }
}
