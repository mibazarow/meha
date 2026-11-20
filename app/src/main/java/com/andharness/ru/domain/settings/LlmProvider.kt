package com.andharness.ru.domain.settings

import com.andharness.ru.domain.llm.LlmAuthConfig

/**
 * A saved OpenAI-compatible provider profile: everything needed to talk to one backend except the
 * API key, which never leaves the encrypted preferences
 * (`com.andharness.ru.data.prefs.LlmSettingsStoreImpl`). Any number of profiles may be stored, but
 * exactly one is ACTIVE at a time — the store mirrors the active profile's endpoint/model into the
 * single `settings` row so every existing consumer (chat client, tools, catalog) keeps reading the
 * same effective configuration it always did.
 *
 * @param id            Room row id; [UNSAVED_ID] for a draft that has not been persisted yet.
 * @param name          user-visible label (optional); blank falls back to the endpoint in the UI.
 * @param endpoint      OpenAI-compatible base URL, e.g. `https://api.deepseek.com/v1/`.
 * @param model         model id sent in chat-completions requests, e.g. `deepseek-flash`.
 * @param auth          how the API key is attached (Bearer / custom header / query / none).
 */
data class LlmProvider(
    val id: Long = UNSAVED_ID,
    val name: String = "",
    val endpoint: String = "",
    val model: String = "",
    val auth: LlmAuthConfig = LlmAuthConfig.DEFAULT,
) {
    /** `true` for a profile that is not stored yet (form draft). */
    val isSaved: Boolean get() = id != UNSAVED_ID

    /**
     * Label to show in the saved-providers list: the explicit name, else the endpoint, else an
     * empty string (the caller substitutes its localized "unnamed" text).
     */
    val displayName: String
        get() = name.trim().ifBlank { endpoint.trim() }

    companion object {
        /** Sentinel id of a provider that has not been persisted (Room would assign a real one). */
        const val UNSAVED_ID: Long = 0L
    }
}
