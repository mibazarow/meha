package com.andharness.ru.domain.communications

/**
 * When the Communications subsystem (background scanning + transcription of ready recordings and
 * audio files) is allowed to run.
 *
 * - [OFF] — never process recordings automatically.
 * - [CHARGING] — process only while the device is charging (the DEFAULT: on-device transcription is
 *   CPU-heavy, so a fresh install does not drain the battery).
 * - [ALWAYS] — process regardless of power state.
 *
 * Phone calls are NOT recorded: Android does not expose call audio to third-party apps. This mode
 * only gates the automatic pass over already-available audio (dictaphone / imported files).
 *
 * Pure JVM (no Android imports) so the persisted-token parsing and labels are unit-testable.
 */
enum class CommunicationsMode(
    /** Stable token persisted in [com.andharness.ru.data.communications.CommunicationsConfigStore]. */
    val id: String,
    /** Human-readable Russian label shown in Settings. */
    val label: String,
) {
    OFF(id = "off", label = "Выкл"),
    CHARGING(id = "charging", label = "На зарядке"),
    ALWAYS(id = "always", label = "Всегда");

    companion object {

        /** Mode used by a fresh install and as the fallback for an unknown stored token. */
        val DEFAULT: CommunicationsMode = CHARGING

        private val BY_ID: Map<String, CommunicationsMode> = entries.associateBy { it.id }

        /**
         * Parses a persisted [id] leniently: `null` or an unknown token falls back to [DEFAULT], so a
         * downgrade or a corrupted pref can never leave the feature in an undefined state.
         */
        fun fromId(id: String?): CommunicationsMode = BY_ID[id] ?: DEFAULT
    }
}
