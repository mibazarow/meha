package com.andharness.ru.data.tools.files

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Android-free MIME → family classification and the curated Google Play fallback list for
 * `files_open` when no installed app can handle a file type.
 *
 * Kept deliberately pure (no `android.*` imports) so it is JVM-unit-testable: the launcher only
 * needs a MIME string and gets back a [Family] plus ready-to-tap Play Store search links. Nothing
 * here queries the PackageManager.
 *
 * Play links: a search URL is used instead of a hardcoded package id, so the suggestion survives an
 * app being renamed/republished, and `&c=apps` constrains the search to apps. `URLEncoder` produces
 * `+` for spaces, which is valid inside a query string.
 * - Play Store web search: https://play.google.com/store/search?q=pdf&c=apps
 * - `market://` scheme (Play app deep link): https://developer.android.com/distribute/marketing-tools/linking-to-google-play
 */
internal object FileOpenMime {

    /** Broad family of a MIME type, used to pick the right Play suggestions. */
    enum class Family { PDF, OFFICE, VIDEO, AUDIO, IMAGE, TEXT, ARCHIVE, EBOOK, OTHER }

    /**
     * A suggested Play Store app: [app] is the visible name, [url] the web Play search link and
     * [market] the tappable `market://` deep link that opens the Play app directly (falling back to
     * [url] when the Play Store is not installed). See
     * https://developer.android.com/distribute/marketing-tools/linking-to-google-play
     */
    data class PlaySuggestion(val app: String, val url: String, val market: String)

    /**
     * A resolved format request. [mime] is the canonical MIME (null when nothing matched), [family]
     * the broad family used to pick suggestions, and [matched] whether the input was understood.
     */
    data class Resolution(val mime: String?, val family: Family, val matched: Boolean)

    /**
     * Resolves a free-form [input] — a MIME (`application/pdf`), a file extension (`pdf`, `.docx`),
     * a friendly kind (`pdf`, `docx`, `rar`, `video`, `office`), or a short free-text task
     * (`открой pdf`, `edit a word document`) — into a canonical MIME + [Family].
     *
     * Order: an explicit MIME (`.../...`) wins, then an extension, then a friendly kind, then a
     * keyword scan of the free text (RU + EN). Unrecognized input returns [Resolution.matched]
     * `false` with a null [Resolution.mime] so callers can answer honestly.
     */
    fun resolve(input: String): Resolution {
        val raw = input.substringBefore(';').trim().lowercase()
        if (raw.isEmpty()) return Resolution(null, Family.OTHER, false)
        if (raw.contains('/')) return Resolution(raw, family(raw), true)
        val token = raw.removePrefix(".").trim()
        EXTENSION_MIMES[token]?.let { return Resolution(it, family(it), true) }
        KIND_MIMES[token]?.let { return Resolution(it, family(it), true) }
        return resolveFreeText(raw)
    }

    /** Keyword scan for a free-text task; the first matching keyword wins. */
    private fun resolveFreeText(text: String): Resolution {
        for ((keyword, mime) in TASK_KEYWORDS) {
            if (text.contains(keyword)) return Resolution(mime, family(mime), true)
        }
        return Resolution(null, Family.OTHER, false)
    }

    /**
     * Maps [mime] to a [Family]. Parameters after `;` (e.g. `text/plain; charset=utf-8`) are
     * ignored, and the match is case-insensitive. Unknown/blank types fall back to [Family.OTHER].
     */
    fun family(mime: String): Family {
        val normalized = mime.substringBefore(';').trim().lowercase()
        return when {
            normalized.isEmpty() -> Family.OTHER
            normalized == "application/pdf" -> Family.PDF
            normalized in OFFICE_MIMES -> Family.OFFICE
            normalized in ARCHIVE_MIMES -> Family.ARCHIVE
            normalized in EBOOK_MIMES -> Family.EBOOK
            normalized.startsWith("text/") -> Family.TEXT
            normalized in TEXT_APPLICATION_MIMES -> Family.TEXT
            normalized.startsWith("image/") -> Family.IMAGE
            normalized.startsWith("video/") -> Family.VIDEO
            normalized.startsWith("audio/") -> Family.AUDIO
            else -> Family.OTHER
        }
    }

    /**
     * At most a handful of well-known, existing Play Store apps for [family]. Empty only when the
     * family has no curated entry (never happens for the families defined here).
     */
    fun playSuggestions(family: Family): List<PlaySuggestion> = SUGGESTIONS[family].orEmpty()

    private fun search(app: String): PlaySuggestion {
        val query = encode(app)
        return PlaySuggestion(
            app = app,
            url = "https://play.google.com/store/search?q=$query&c=apps",
            market = "market://search?q=$query",
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    // Curated extension → canonical MIME. Small on purpose: the common formats an agent meets, not
    // every registered type. Family is derived from the MIME via [family], so the two stay in sync.
    private val EXTENSION_MIMES: Map<String, String> = mapOf(
        // documents / office
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf",
        // text
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "html" to "text/html",
        "htm" to "text/html",
        // archives
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2",
        // ebooks
        "epub" to "application/epub+zip",
        "mobi" to "application/x-mobipocket-ebook",
        "azw3" to "application/vnd.amazon.ebook",
        "fb2" to "application/x-fictionbook+xml",
        // images
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "svg" to "image/svg+xml",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        // audio
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "aac" to "audio/aac",
        "m4a" to "audio/mp4",
        // video
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
    )

    // Friendly kind (what a user/agent may say) → a representative MIME whose [family] picks the
    // right suggestions. `office`/`document` map to Word so the OFFICE family (docs/sheets/slides)
    // is suggested as a group.
    private val KIND_MIMES: Map<String, String> = mapOf(
        "pdf" to "application/pdf",
        "office" to "application/msword",
        "document" to "application/msword",
        "word" to "application/msword",
        "docx" to "application/msword",
        "excel" to "application/vnd.ms-excel",
        "spreadsheet" to "application/vnd.ms-excel",
        "sheet" to "application/vnd.ms-excel",
        "powerpoint" to "application/vnd.ms-powerpoint",
        "presentation" to "application/vnd.ms-powerpoint",
        "slides" to "application/vnd.ms-powerpoint",
        "archive" to "application/zip",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "ebook" to "application/epub+zip",
        "book" to "application/epub+zip",
        "epub" to "application/epub+zip",
        "text" to "text/plain",
        "txt" to "text/plain",
        "image" to "image/jpeg",
        "photo" to "image/jpeg",
        "picture" to "image/jpeg",
        "audio" to "audio/mpeg",
        "music" to "audio/mpeg",
        "sound" to "audio/mpeg",
        "video" to "video/mp4",
        "movie" to "video/mp4",
    )

    // Substring → MIME scan for a free-text task (RU + EN). Kept short and unambiguous.
    private val TASK_KEYWORDS: List<Pair<String, String>> = listOf(
        "pdf" to "application/pdf",
        "word" to "application/msword",
        "docx" to "application/msword",
        "документ" to "application/msword",
        "excel" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.ms-excel",
        "таблиц" to "application/vnd.ms-excel",
        "powerpoint" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.ms-powerpoint",
        "презентац" to "application/vnd.ms-powerpoint",
        "office" to "application/msword",
        "офис" to "application/msword",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "архив" to "application/zip",
        "epub" to "application/epub+zip",
        "книг" to "application/epub+zip",
        "image" to "image/jpeg",
        "photo" to "image/jpeg",
        "picture" to "image/jpeg",
        "фото" to "image/jpeg",
        "картинк" to "image/jpeg",
        "audio" to "audio/mpeg",
        "аудио" to "audio/mpeg",
        "музык" to "audio/mpeg",
        "видео" to "video/mp4",
        "video" to "video/mp4",
    )

    // Well-known document formats. MimeTypeMap can return the modern OOXML/ODF types as well as the
    // legacy `application/msword`-family types, so both spellings are listed.
    private val OFFICE_MIMES = setOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/rtf",
    )

    private val ARCHIVE_MIMES = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-rar-compressed",
        "application/vnd.rar",
        "application/x-7z-compressed",
        "application/x-tar",
        "application/gzip",
        "application/x-gzip",
        "application/x-bzip2",
        "application/x-compressed",
    )

    private val EBOOK_MIMES = setOf(
        "application/epub+zip",
        "application/x-mobipocket-ebook",
        "application/vnd.amazon.ebook",
        "application/x-fictionbook+xml",
    )

    private val TEXT_APPLICATION_MIMES = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-yaml",
        "application/yaml",
    )

    private val SUGGESTIONS: Map<Family, List<PlaySuggestion>> = mapOf(
        Family.PDF to listOf(
            search("Adobe Acrobat Reader"),
            search("Foxit PDF Reader"),
            search("Xodo PDF Reader"),
            search("Google Drive"),
        ),
        Family.OFFICE to listOf(
            search("Google Docs"),
            search("Microsoft Word"),
            search("Google Sheets"),
            search("WPS Office"),
        ),
        Family.VIDEO to listOf(
            search("VLC for Android"),
            search("MX Player"),
            search("MPV"),
        ),
        Family.AUDIO to listOf(
            search("VLC for Android"),
            search("Musicolet"),
            search("AIMP"),
        ),
        Family.IMAGE to listOf(
            search("Google Photos"),
            search("Google Files"),
            search("Google Drive"),
        ),
        Family.TEXT to listOf(
            search("QuickEdit Text Editor"),
            search("Google Drive"),
            search("Google Files"),
        ),
        Family.ARCHIVE to listOf(
            search("ZArchiver"),
            search("RAR for Android"),
            search("Google Files"),
        ),
        Family.EBOOK to listOf(
            search("Google Play Books"),
            search("Moon+ Reader"),
            search("ReadEra"),
            search("Librera Reader"),
        ),
        Family.OTHER to listOf(
            search("Google Files"),
            search("Google Drive"),
            search("VLC for Android"),
        ),
    )
}
