package com.andharness.ru.data.screenmap

import com.andharness.ru.domain.screenmap.AppScreenMap
import com.andharness.ru.domain.screenmap.ScreenElementInfo
import com.andharness.ru.domain.screenmap.ScreenInfo
import com.andharness.ru.domain.screenmap.ScreenMapCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persistent per-app screen map (knowledge cache): one JSON file `<rootDir>/screen_map.json` written
 * atomically (temp file + rename). It is deliberately **Android-free** — it only takes a directory
 * [File] — so the whole store (CRUD, bounds/eviction, dedup, corrupt-file degradation) is exercised
 * on the JVM (`ScreenMapStoreTest`); the DI module supplies `screenmap` under `context.filesDir`.
 *
 * This is a CACHE of what the agent has already seen, not a source of truth: it may be stale, so it
 * is only ever used to orient quickly and every action must still be verified against a fresh
 * `ui_tree`. It is bounded ([ScreenMapCodec]) at [ScreenMapCodec.MAX_APPS] apps,
 * [ScreenMapCodec.MAX_SCREENS_PER_APP] screens/app and [ScreenMapCodec.MAX_ELEMENTS_PER_SCREEN]
 * elements/screen; the least-recently-seen entry is evicted first.
 *
 * All access is `@Synchronized`; a corrupt/foreign file degrades to an empty map instead of throwing.
 * Every write failure is best-effort and leaves the in-memory state unchanged, so a failed disk write
 * is visible rather than silently reported as success.
 */
class ScreenMapStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _maps = MutableStateFlow(load())

    /** Current map as an observable list (oldest-seen app first). */
    val maps: StateFlow<List<AppScreenMap>> = _maps.asStateFlow()

    /** All app maps, never throws. */
    @Synchronized
    fun list(): List<AppScreenMap> = _maps.value

    /** The map for [appPackage] (exact match first, then case-insensitive), or `null`. */
    @Synchronized
    fun get(appPackage: String): AppScreenMap? {
        val target = appPackage.trim()
        if (target.isEmpty()) return null
        return _maps.value.firstOrNull { it.appPackage == target }
            ?: _maps.value.firstOrNull { it.appPackage.equals(target, ignoreCase = true) }
    }

    /**
     * Records one observed screen of [appPackage], identified by a stable [screenSignature], with its
     * notable [elements] and [now]. Upserts by signature (the newest observation replaces the stored
     * elements and moves the screen/app to the most-recently-seen end), keeps a previous [title] when
     * the new one is absent, and then applies every bound. Returns `false` only for a blank
     * package/signature (an anonymous observation is never stored).
     */
    @Synchronized
    fun record(
        appPackage: String,
        screenSignature: String,
        elements: List<ScreenElementInfo>,
        now: Long = System.currentTimeMillis(),
        title: String? = null,
    ): Boolean {
        val pkg = appPackage.trim()
        val signature = screenSignature.trim()
        if (pkg.isEmpty() || signature.isEmpty()) return false

        val existingScreens = _maps.value.firstOrNull { it.appPackage == pkg }?.screens ?: emptyList()
        val previous = existingScreens.firstOrNull { it.signature == signature }
        val recorded = ScreenInfo(
            signature = signature,
            title = title?.trim()?.takeIf { it.isNotBlank() }?.take(ScreenMapCodec.MAX_FIELD_CHARS)
                ?: previous?.title,
            lastSeen = now.coerceAtLeast(0L),
            elements = ScreenMapCodec.dedupeAndCap(elements),
        )
        val updatedMap = AppScreenMap(
            appPackage = pkg,
            screens = existingScreens
                .filterNot { it.signature == signature }
                .plus(recorded)
                .takeLast(ScreenMapCodec.MAX_SCREENS_PER_APP),
        )
        persist(
            _maps.value
                .filterNot { it.appPackage == pkg }
                .plus(updatedMap)
                .takeLast(ScreenMapCodec.MAX_APPS),
        )
        return true
    }

    /**
     * Forgets one app ([appPackage] non-blank, case-insensitive), or the whole map when it is
     * blank/`null`. Returns the number of apps removed (0 for an unknown package or an empty map).
     */
    @Synchronized
    fun forget(appPackage: String?): Int {
        val target = appPackage?.trim().orEmpty()
        if (target.isEmpty()) {
            val removed = _maps.value.size
            if (removed > 0) persist(emptyList())
            return removed
        }
        val next = _maps.value.filterNot { it.appPackage.equals(target, ignoreCase = true) }
        val removed = _maps.value.size - next.size
        if (removed > 0) persist(next)
        return removed
    }

    private fun load(): List<AppScreenMap> = try {
        if (file.isFile) ScreenMapCodec.decode(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(maps: List<AppScreenMap>) {
        try {
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            temp.writeText(ScreenMapCodec.encode(maps))
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(ScreenMapCodec.encode(maps))
                temp.delete()
            }
            _maps.value = maps
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible
            // (the map simply does not change) rather than silently reporting success.
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the screen map. */
        const val DIR_NAME = "screenmap"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "screen_map.json"
    }
}
