package com.andharness.ru.data.messages

import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.messages.MessageQueryEmbedder
import com.andharness.ru.domain.retrieval.SemanticSearchGate
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [MessageQueryEmbedder]: embeds a search query with the bundled, fully-offline
 * [LocalEmbedder], but only when the `semanticSearchEnabled` switch is on and the text is worth
 * embedding. The model call is bounded by [EMBED_TIMEOUT_MS] and wrapped so a missing asset, an ORT
 * error or a timeout degrades to lexical-only search instead of failing the tool.
 *
 * Reference: ONNX Runtime Java API / `LocalEmbedder`
 * (https://onnxruntime.ai/docs/api/java/); gate semantics: [SemanticSearchGate].
 */
@Singleton
class LocalMessageQueryEmbedder @Inject constructor(
    private val localEmbedder: LocalEmbedder,
    private val settingsStore: LlmSettingsStore,
) : MessageQueryEmbedder {

    override suspend fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        val enabled = runCatching {
            settingsStore.config.firstOrNull()?.semanticSearchEnabled == true
        }.getOrDefault(false)
        if (!SemanticSearchGate.shouldEmbed(enabled = enabled, text = text)) return null
        return runCatching {
            withTimeoutOrNull(EMBED_TIMEOUT_MS) {
                localEmbedder.embed(listOf(text)).getOrNull()?.firstOrNull()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        /** Bounds one query inference; on timeout the search silently falls back to FTS only. */
        const val EMBED_TIMEOUT_MS = 5_000L
    }
}
