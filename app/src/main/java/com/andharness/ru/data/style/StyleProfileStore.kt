package com.andharness.ru.data.style

import com.andharness.ru.domain.style.StyleMode
import com.andharness.ru.domain.style.StyleProfile
import com.andharness.ru.domain.style.StyleProfileReducer
import com.andharness.ru.domain.style.StyleSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persistent, cross-chat style profile backed by one JSON file `<rootDir>/style/style.json`, written
 * atomically (temp file + rename) like [com.andharness.ru.data.profile.GlobalProfileStore].
 *
 * Deliberately Android-free (it only takes a directory [File]), so observation, bounds, persistence
 * and corrupt-file degradation are exercised on the JVM. All access is `@Synchronized`; a failed
 * disk write leaves the in-memory profile unchanged, so the failure is visible rather than silently
 * reported as success.
 */
class StyleProfileStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _profile = MutableStateFlow(load())

    /** Current profile as an observable value (settings UI, diagnostics). */
    val profile: StateFlow<StyleProfile> = _profile.asStateFlow()

    /** Snapshot of the current profile. Never throws. */
    @Synchronized
    fun current(): StyleProfile = _profile.value

    /**
     * Folds one meaningful user message into the profile and persists it. Returns the stored
     * snapshot; a disk failure leaves the previous snapshot in place.
     */
    @Synchronized
    fun observe(signal: StyleSignal, now: Long, day: Long): StyleProfile {
        val current = _profile.value
        val next = StyleProfileRules.sanitize(
            StyleProfileReducer.apply(current, signal, now, day)
        )
        if (next == current) return current
        persist(next)
        return _profile.value
    }

    /** Applies a pure transformation, sanitizes it and persists only when something changed. */
    @Synchronized
    fun update(transform: (StyleProfile) -> StyleProfile): StyleProfile {
        val current = _profile.value
        val next = StyleProfileRules.sanitize(transform(current))
        if (next == current) return current
        persist(next)
        return _profile.value
    }

    /** Switches adaptation mode. Returns true when the stored profile changed. */
    @Synchronized
    fun setMode(mode: StyleMode): Boolean {
        val current = _profile.value
        if (current.mode == mode) return false
        persist(current.copy(mode = mode))
        return true
    }

    /** Enables or disables mirroring mild swearing. Returns true when the profile changed. */
    @Synchronized
    fun setProfanityEnabled(enabled: Boolean): Boolean {
        val current = _profile.value
        if (current.profanityEnabled == enabled) return false
        persist(current.copy(profanityEnabled = enabled))
        return true
    }

    /**
     * Saves a user-authored card. Blank clears it; an overlong card is rejected (returns false) so
     * the user's text is never silently truncated.
     */
    @Synchronized
    fun setManualCard(raw: String?): Boolean {
        val clean = if (raw.isNullOrBlank()) null else StyleProfileRules.normalizeCard(raw) ?: return false
        val current = _profile.value
        if (current.manualCard == clean) return false
        persist(current.copy(manualCard = clean))
        return true
    }

    /** Saves a model-produced card, capping it to the hard limit. Blank clears it. */
    @Synchronized
    fun setCard(raw: String?, now: Long): Boolean {
        val clean = StyleProfileRules.capCard(raw)
        val current = _profile.value
        if (current.card == clean && (clean == null || current.cardUpdatedAt == now)) return false
        persist(current.copy(card = clean, cardUpdatedAt = if (clean == null) 0L else now))
        return true
    }

    /** Forgets everything learned while preserving the user's mode and profanity preference. */
    @Synchronized
    fun clear(): Boolean {
        val current = _profile.value
        val next = StyleProfile(
            mode = current.mode,
            profanityEnabled = current.profanityEnabled,
        )
        if (current == next) return false
        persist(next)
        return true
    }

    private fun load(): StyleProfile = try {
        if (file.isFile) StyleProfileCodec.decode(file.readText()) else StyleProfile()
    } catch (_: Exception) {
        StyleProfile()
    }

    private fun persist(next: StyleProfile) {
        try {
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            val encoded = StyleProfileCodec.encode(next)
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                file.writeText(encoded)
                temp.delete()
            }
            _profile.value = StyleProfileRules.sanitize(next)
        } catch (_: Exception) {
            // Best-effort: keep the previous in-memory state so a failed write is visible.
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the style profile. */
        const val DIR_NAME = "style"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "style.json"
    }
}
