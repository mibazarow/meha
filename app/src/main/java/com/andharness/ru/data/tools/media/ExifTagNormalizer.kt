package com.andharness.ru.data.tools.media

/**
 * Normalizes an `exif_write` `tag` argument to the canonical
 * `androidx.exifinterface.media.ExifInterface.TAG_*` value the writer expects.
 *
 * Two spellings are accepted for every writable tag, case-insensitively:
 * 1. the short Android/EXIF name, which is exactly the value of the matching
 *    `ExifInterface.TAG_*` constant (`DateTimeOriginal`, `GPSLatitude`, ...), and
 * 2. the `TAG_*` constant name itself (`TAG_DATETIME_ORIGINAL`, `TAG_GPS_LATITUDE`, ...).
 *
 * Underscore placement and case are both insignificant, so mixed spellings such as
 * `TAG_DateTimeOriginal` (a plausible model output) also resolve to `DateTimeOriginal`.
 *
 * This helper is intentionally pure (no Android imports) so it stays unit-testable on the JVM.
 * The canonical strings below must stay equal to the `ExifInterface.TAG_*` constant values;
 * callers additionally restrict the result to their own allow-list.
 *
 * Source for the constant names and their values:
 * https://developer.android.com/reference/kotlin/androidx/exifinterface/media/ExifInterface
 * (`TAG_DATETIME_ORIGINAL = "DateTimeOriginal"`, `TAG_GPS_LATITUDE = "GPSLatitude"`, ...),
 * mirrored in
 * https://android.googlesource.com/platform/frameworks/support/+/a9ac247af2afd4115c3eb6d16c05bc92737d6305/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java
 */
internal object ExifTagNormalizer {

    /** `TAG_*` constant name to canonical `ExifInterface.TAG_*` value, in display order. */
    private val WRITABLE_TAGS: List<Pair<String, String>> = listOf(
        "TAG_DATETIME" to "DateTime",
        "TAG_DATETIME_ORIGINAL" to "DateTimeOriginal",
        "TAG_MAKE" to "Make",
        "TAG_MODEL" to "Model",
        "TAG_ORIENTATION" to "Orientation",
        "TAG_SOFTWARE" to "Software",
        "TAG_ARTIST" to "Artist",
        "TAG_IMAGE_DESCRIPTION" to "ImageDescription",
        "TAG_COPYRIGHT" to "Copyright",
        "TAG_GPS_LATITUDE" to "GPSLatitude",
        "TAG_GPS_LATITUDE_REF" to "GPSLatitudeRef",
        "TAG_GPS_LONGITUDE" to "GPSLongitude",
        "TAG_GPS_LONGITUDE_REF" to "GPSLongitudeRef",
    )

    /** Accepted tag forms, both spellings per tag, for the validation error message. */
    val acceptedForms: String =
        WRITABLE_TAGS.joinToString(", ") { (constant, canonical) -> "$canonical ($constant)" }

    /**
     * Resolves a raw `tag` argument to the canonical `ExifInterface.TAG_*` value, or returns
     * `null` when the tag is not writable. Input is trimmed; matching ignores case. Both the
     * short name (`DateTimeOriginal`) and the constant name (`TAG_DATETIME_ORIGINAL`) resolve
     * to the same canonical value.
     */
    fun normalize(raw: String): String? {
        val key = raw.trim()
        if (key.isEmpty()) return null
        val keyBare = key.replace("_", "")
        return WRITABLE_TAGS.firstOrNull { (constant, canonical) ->
            key.equals(canonical, ignoreCase = true) ||
                key.equals(constant, ignoreCase = true) ||
                keyBare.equals(canonical.replace("_", ""), ignoreCase = true) ||
                keyBare.equals(constant.replace("_", ""), ignoreCase = true)
        }?.second
    }
}
