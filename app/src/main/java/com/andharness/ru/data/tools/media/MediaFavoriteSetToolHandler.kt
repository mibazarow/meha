package com.andharness.ru.data.tools.media

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiConsentPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `media_favorite_set`: adds or removes media items from favourites via the consent dialog. */
internal class MediaFavoriteSetToolHandler(
    private val context: Context,
    private val io: MediaPathIo,
    private val consent: MediaConsentRequester,
) : ToolHandler {
    override val name = "media_favorite_set"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val raw = (arguments["paths"] as? JsonPrimitive)?.content.orEmpty()
            val resolved = io.resolveMediaPaths(raw)
            resolved.error?.let { return@withContext ToolResult(false, "$it Изменение избранного отменено.") }
            val files = resolved.files

            val favorite = (arguments["favorite"] as? JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: true

            // MediaStore.createFavoriteRequest requires API 30 (Android 11+).
            // https://developer.android.com/training/data-storage/shared/media
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@withContext ToolResult(
                    false,
                    "Добавление медиа в избранное требует Android 11 (API 30) или новее.",
                )
            }

            val (uris, skipped) = io.mediaUrisFor(files)
            if (uris.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Ни один файл не найден в медиатеке: ${skipped.joinToString(", ")}.",
                )
            }

            // The change only happens after the user confirms in the system dialog, which
            // the Activity launches from our IntentSender.
            val request = MediaStore.createFavoriteRequest(context.contentResolver, uris, favorite)
            val consentResult = consent.request(
                request.intentSender,
                if (favorite) "favorite" else "unfavorite",
                timeoutMs = MEDIA_FAVORITE_TIMEOUT_MS,
            )
            consent.errorMessage(
                consentResult,
                declined = "Пользователь отклонил изменение избранного.",
                cancelled = "Изменение избранного отменено: диалог закрыт.",
            )?.let { return@withContext ToolResult(false, it) }

            // POST-VERIFY: each item counts as changed when IS_FAVORITE matches the request.
            // Reference: MediaStore.MediaColumns.IS_FAVORITE —
            // https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_FAVORITE
            val projection = arrayOf(MediaStore.MediaColumns.IS_FAVORITE)
            var matched = 0
            for (uri in uris) {
                val isFavorite = try {
                    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                        c.moveToFirst() &&
                            c.getInt(
                                c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE),
                            ) == 1
                    } ?: false
                } catch (e: Exception) {
                    Log.e("MediaToolsHandler", "media_favorite_set verify failed for $uri", e)
                    false
                }
                if (isFavorite == favorite) matched++
            }

            val remaining = uris.size - matched
            val report = StringBuilder(
                if (favorite) {
                    "Добавлено в избранное $matched из ${uris.size}."
                } else {
                    "Убрано из избранного $matched из ${uris.size}."
                },
            )
            if (skipped.isNotEmpty()) {
                report.append(" Пропущены (нет в медиатеке): ${skipped.joinToString(", ")}.")
            }
            if (remaining > 0) {
                report.append(" Не удалось изменить: $remaining.")
            }
            ToolResult(matched > 0, report.toString())
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "media_favorite_set failed", e)
            ToolResult(false, "Не удалось изменить избранное: ${e.message}")
        }
    }

    private companion object {
        /**
         * `media_favorite_set` maximum time to wait for the user to confirm the favorite request.
         * Bounded by [UiConsentPolicy.TIMEOUT_MS] for the same reason (BUG-014).
         */
        private const val MEDIA_FAVORITE_TIMEOUT_MS = UiConsentPolicy.TIMEOUT_MS
    }
}
