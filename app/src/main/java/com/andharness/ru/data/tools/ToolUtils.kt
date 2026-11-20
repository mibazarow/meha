package com.andharness.ru.data.tools

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.andharness.ru.domain.serialization.JsonArgs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Name of the app's working directory for tool file operations. */
private const val TOOL_ROOT_DIR_NAME = "meha"

/**
 * Root directory for tool file operations (app-private storage): the `meha` subdirectory of the
 * app's external files dir ([Context.getExternalFilesDir]), falling back to [Context.filesDir]
 * when external storage is not mounted. The subdirectory is created on demand via [File.mkdirs],
 * so it always exists once this returns. It is the app's working directory for file tools.
 */
fun Context.toolRootDir(): File =
    File(getExternalFilesDir(null) ?: filesDir, TOOL_ROOT_DIR_NAME).apply { mkdirs() }

/** Parses a `limit` argument; default 50, min 1. */
fun parseLimit(value: JsonPrimitive?): Int = (value?.content?.toIntOrNull() ?: 50).coerceAtLeast(1)

/**
 * Primary shared/external storage root (e.g. /storage/emulated/0).
 * Reference: Environment.getExternalStorageDirectory() —
 * https://developer.android.com/reference/android/os/Environment#getExternalStorageDirectory()
 */
fun Context.publicStorageRoot(): File = Environment.getExternalStorageDirectory()

/**
 * Root the file tools operate on. Returns the real public shared storage root
 * when MANAGE_EXTERNAL_STORAGE special access is granted, otherwise the
 * app-private dir (limited mode).
 * Reference: Build.VERSION.SDK_INT —
 * https://developer.android.com/reference/android/os/Build.VERSION#SDK_INT
 * and Environment.isExternalStorageManager() (added in API 30) —
 * https://developer.android.com/reference/android/os/Environment#isExternalStorageManager()
 * and https://developer.android.com/training/data-storage/manage-all-files
 */
fun Context.effectiveToolRoot(): File =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        publicStorageRoot()
    else
        toolRootDir()

/**
 * All storage roots the file tools may address: the primary [effectiveToolRoot] plus, on API 30+,
 * the directory of every storage volume the system reports as mounted (this includes removable
 * SD-card / USB volumes such as /storage/XXXX-XXXX). Roots are canonicalized and deduplicated by
 * path, because the primary volume is also returned by [StorageManager.getStorageVolumes].
 * On API < 30 only the primary root is available — [StorageVolume.getDirectory] does not exist
 * before API 30.
 * References:
 * - StorageManager — https://developer.android.com/reference/android/os/storage/StorageManager
 * - StorageVolume#getDirectory() (added in API 30) —
 *   https://developer.android.com/reference/android/os/storage/StorageVolume#getDirectory()
 * - Build.VERSION.SDK_INT —
 *   https://developer.android.com/reference/android/os/Build.VERSION#SDK_INT
 */
fun Context.allowedToolRoots(): List<File> {
    val roots = mutableListOf(effectiveToolRoot())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val storageManager = getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (storageManager != null) {
            for (volume in storageManager.storageVolumes) {
                val dir = volume.directory ?: continue
                if (dir.isDirectory) roots += dir
            }
        }
    }
    return roots.map { it.canonicalFile }.distinctBy { it.path }
}

private val WINDOWS_DRIVE_PREFIX = Regex("""^[A-Za-z]:[/\\]""")

/**
 * Single-root convenience overload of [resolveSafePath] for callers that address one root only.
 */
fun resolveSafePath(root: File, raw: String): File = resolveSafePath(listOf(root), raw)

/**
 * Resolves [raw] against [roots] and guarantees that the result stays inside exactly one of them
 * (path containment). Rejects blank paths, NUL bytes and any path containing a `..` segment.
 * An absolute path (`/…`, `C:\…` / `C:/…`) is accepted only when its canonical form lies inside
 * one of [roots]; a relative path is resolved against the first root that contains it. After
 * canonicalization the result must equal a root or start with `root + File.separator`.
 * This blocks traversal (`../../etc/passwd`) and prefix-confusion escapes
 * (`/storage/emulated/0/Documents2` is NOT inside `/storage/emulated/0/Documents`).
 * Pure `java.io.File` — no Android APIs, so it is unit-testable on the JVM.
 * Reference: `java.io.File#getCanonicalPath()` / `#getCanonicalFile()` —
 * https://developer.android.com/reference/java/io/File
 *
 * @throws IllegalArgumentException with a Russian, user-facing message when the path is
 *         invalid or escapes every root in [roots].
 */
fun resolveSafePath(roots: List<File>, raw: String): File {
    if (raw.isBlank()) {
        throw IllegalArgumentException("Путь не указан или пустой.")
    }
    if (raw.contains('\u0000')) {
        throw IllegalArgumentException("Путь содержит недопустимый символ NUL.")
    }
    if (raw.split('/', '\\').any { it == ".." }) {
        throw IllegalArgumentException("Путь не должен содержать «..»: «$raw».")
    }
    if (roots.isEmpty()) {
        throw IllegalArgumentException("Путь вне доступного хранилища: «$raw». Доступных корней нет.")
    }
    if (raw.startsWith("/") || WINDOWS_DRIVE_PREFIX.containsMatchIn(raw)) {
        val candidate = File(raw).canonicalFile
        if (roots.any { contains(it.canonicalFile, candidate) }) return candidate
        throw IllegalArgumentException(boundaryMessage(roots, raw))
    }
    for (root in roots) {
        val rootCanon = root.canonicalFile
        val candidate = File(root, raw).canonicalFile
        if (contains(rootCanon, candidate)) return candidate
    }
    throw IllegalArgumentException(boundaryMessage(roots, raw))
}

/**
 * The refusal names the boundary itself — which roots ARE addressable — so the model can correct the
 * path on the next call instead of retrying blind guesses against an unnamed "access error"
 * (TOOLS_TASKS §4). Roots are rendered canonical and best-effort: a root that cannot be canonicalised
 * is shown by its absolute path.
 */
private fun boundaryMessage(roots: List<File>, raw: String): String {
    val allowed = roots.joinToString(", ") { root ->
        runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    }
    return "Путь вне доступного хранилища: «$raw». Доступно только внутри: $allowed."
}

/**
 * Separator-aware containment: [path] is inside [rootCanon] only when it equals it or continues
 * with a path separator, so a sibling sharing a name prefix never passes.
 */
private fun contains(rootCanon: File, path: File): Boolean =
    path.path == rootCanon.path || path.path.startsWith(rootCanon.path + File.separator)

/**
 * Creates a [MediaRecorder] on every supported API level. The `MediaRecorder(Context)` constructor
 * was added in API 31; on API 26–30 the deprecated no-arg constructor is the only one available,
 * so calling `MediaRecorder(context)` unconditionally would crash with `NoSuchMethodError`.
 * References:
 * - `MediaRecorder(Context)` (added in API 31) —
 *   https://developer.android.com/reference/android/media/MediaRecorder#MediaRecorder(android.content.Context)
 * - `Build.VERSION.SDK_INT` —
 *   https://developer.android.com/reference/android/os/Build.VERSION#SDK_INT
 */
@Suppress("DEPRECATION")
fun newMediaRecorder(context: Context): MediaRecorder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

/** Copy buffer used by [readBounded]. */
private const val READ_BOUNDED_BUFFER_SIZE = 8 * 1024

/** Outcome of [readBounded]: the bytes actually read plus whether the source had more. */
data class BoundedRead(val bytes: ByteArray, val truncated: Boolean)

/**
 * Reads at most [maxBytes] from [input] so an untrusted (e.g. HTTP) body can never exhaust memory.
 * [BoundedRead.truncated] is true when the source still had data after the cap; one extra byte is
 * probed to tell an exact-cap body from a truncated one. Pure `java.io` — unit-testable on the JVM.
 */
fun readBounded(input: InputStream, maxBytes: Long): BoundedRead {
    require(maxBytes > 0L) { "maxBytes должен быть положительным." }
    val buffer = ByteArray(READ_BOUNDED_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var total = 0L
    while (total < maxBytes) {
        val want = minOf(buffer.size.toLong(), maxBytes - total).toInt()
        val read = input.read(buffer, 0, want)
        if (read <= 0) break
        output.write(buffer, 0, read)
        total += read
    }
    val truncated = total >= maxBytes && input.read() >= 0
    return BoundedRead(output.toByteArray(), truncated)
}

/**
 * Nullable string tool argument: the raw JSON content of [key], or `null` when the key is absent
 * or blank. Thin delegate to the canonical domain helper [JsonArgs.str]; kept under this name and
 * package so the many `import com.andharness.ru.data.tools.str` call sites stay unchanged.
 */
fun JsonObject.str(key: String): String? = JsonArgs.str(this, key)

/**
 * Lenient boolean tool argument: trims and lower-cases the content and maps the common English and
 * Russian true/false tokens (`true/1/on/yes/да/вкл` and their negatives). Returns `null` for an
 * absent or unrecognized value, so a caller can tell "not given" from "given as false".
 * Single shared implementation of the former per-handler `boolOrNull` copies.
 * [Locale.ROOT] keeps case folding locale-independent (e.g. on a Turkish device); this matches the
 * two locale-safe copies and is equivalent to the default-locale copy for the accepted tokens, none
 * of which contain the letter `i`/`I`, so no call site changes behavior.
 * Reference: `String.lowercase(Locale)` —
 * https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/lowercase.html
 */
fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.lowercase(Locale.ROOT)?.let { raw ->
        when (raw) {
            "true", "1", "on", "yes", "да", "вкл" -> true
            "false", "0", "off", "no", "нет", "выкл" -> false
            else -> null
        }
    }

/**
 * Human-readable size: Б / КБ / МБ / ГБ (binary units), one decimal from КБ up. [Locale.ROOT]
 * keeps the decimal separator a dot on every device locale so the reported value is stable.
 */
fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value < 1024L -> "$value Б"
        value < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f КБ", value / 1024.0)
        value < 1024L * 1024L * 1024L ->
            String.format(Locale.ROOT, "%.1f МБ", value / (1024.0 * 1024.0))
        else -> String.format(Locale.ROOT, "%.1f ГБ", value / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * Local-time stamp for [epochMs] formatted with [pattern] (`dd.MM.yyyy HH:mm` by default).
 * java.time is available from API 26 (minSdk 26); [Locale.ROOT] keeps the output stable.
 */
fun formatTimestamp(epochMs: Long, pattern: String = "dd.MM.yyyy HH:mm"): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT))

/** Compact duration `2 ч 5 мин 3 с`; hours/minutes are omitted while still zero. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) append("$hours ч ")
        if (hours > 0 || minutes > 0) append("$minutes мин ")
        append("$seconds с")
    }
}
