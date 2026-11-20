package com.andharness.ru.data.tools.index

import android.util.Log
import com.andharness.ru.data.index.DeviceIndexKind
import com.andharness.ru.data.index.DeviceSearchOutcome
import com.andharness.ru.data.index.DeviceSearchService
import com.andharness.ru.data.index.DeviceSearchStatus
import com.andharness.ru.data.index.MediaStorePathResolver
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.DeviceSearchCard
import com.andharness.ru.domain.chat.DeviceSearchPayload
import com.andharness.ru.domain.tools.ToolHandler
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `device_search`: semantic ("по смыслу") search over the whole-device index — gallery photos and
 * indexed document text — via [DeviceSearchService]. The query is embedded on device and ranked by
 * cosine similarity, so the agent can find a file by meaning instead of an exact name.
 *
 * Each hit is rendered as an actionable line ([DeviceSearchFormatter]): the `content://` URI plainly,
 * the absolute path when [MediaStorePathResolver] can derive it, and — for images — the MediaStore
 * id, followed by a footer pointing at the `media_open` / `media_share` tools. The tool needs no
 * extra runtime permission (the index was already built from MediaStore/SAF reads), so it does NOT
 * request `MANAGE_EXTERNAL_STORAGE`. Every failure is caught and reported; nothing throws.
 */
internal class DeviceSearchToolHandler(
    private val service: DeviceSearchService,
    private val pathResolver: MediaStorePathResolver,
) : ToolHandler {

    override val name = "device_search"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = (arguments["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (query.isEmpty()) {
                return@withContext ToolResult(false, "Укажите query — что искать на устройстве.")
            }
            val kind = normalizeKind((arguments["type"] as? JsonPrimitive)?.content)
            val limit = ((arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_LIMIT)
                .coerceIn(1, MAX_LIMIT)
            val outcome = service.searchDetailed(query, kind, limit)
            // The text is the agent/user log; the payload is the structured form the chat renders
            // as cards (photo thumbnails / document icons / fallback links). It is present ONLY for a
            // genuine, non-empty hit list — every honest short-circuit (no match, empty index,
            // embedder unavailable) carries no payload.
            ToolResult(true, render(query, outcome), payload = buildPayload(outcome))
        } catch (e: Exception) {
            Log.e(TAG, "device_search failed", e)
            ToolResult(false, "Ошибка поиска по устройству: ${e.message}")
        }
    }

    /** `any`/blank → no filter; `image` → [DeviceIndexKind.IMAGE]; `document` → DOC_CHUNK. */
    private fun normalizeKind(raw: String?): DeviceIndexKind? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "image", "photo", "фото", "изображение" -> DeviceIndexKind.IMAGE
        "document", "doc", "документ" -> DeviceIndexKind.DOC_CHUNK
        else -> null
    }

    private fun render(query: String, outcome: DeviceSearchOutcome): String = when (outcome.status) {
        DeviceSearchStatus.EMBEDDER_UNAVAILABLE ->
            "Семантический поиск по устройству недоступен: офлайн-модель эмбеддингов ещё не скачана " +
                "или не запустилась. Индекс не прочитан — попробуйте позже."

        DeviceSearchStatus.EMPTY_INDEX ->
            "Индекс устройства пока пуст — искать нечего. Фото и документы наполняются фоновым " +
                "индексатором по мере работы приложения."

        DeviceSearchStatus.NO_MATCH ->
            "По запросу «$query» ничего не найдено среди проиндексированных фото и документов."

        DeviceSearchStatus.OK -> buildString {
            append("Найдено результатов: ")
            append(outcome.results.size)
            append(" по запросу «")
            append(query)
            appendLine("»:")
            outcome.results.forEach { result ->
                appendLine(
                    DeviceSearchFormatter.formatLine(result, pathResolver.absolutePath(result.sourceUri))
                )
            }
            append(DeviceSearchFormatter.footer())
        }.trimEnd()
    }

    /** The structured cards for the chat preview, or `null` when there is nothing to show. */
    private fun buildPayload(outcome: DeviceSearchOutcome): String? {
        if (outcome.status != DeviceSearchStatus.OK || outcome.results.isEmpty()) return null
        val cards = outcome.results.map { result ->
            DeviceSearchCard(
                kind = DeviceSearchPayload.classify(result.kind.name, result.mimeType),
                uri = result.sourceUri,
                displayName = result.displayName,
                mimeType = result.mimeType,
                snippet = result.snippet,
            )
        }
        return DeviceSearchPayload.encode(cards)
    }

    private companion object {
        const val TAG = "Andharness"
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
    }
}
