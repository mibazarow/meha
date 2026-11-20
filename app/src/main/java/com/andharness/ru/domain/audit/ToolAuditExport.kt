package com.andharness.ru.domain.audit

import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Pure JSON/CSV rendering of the audit log (update.md idea #6). Keeping it free of Android/Room
 * types makes the export format unit-testable. JSON is escaped by kotlinx.serialization; CSV fields
 * are quoted per RFC 4180 when they contain a delimiter, quote or newline.
 */
object ToolAuditExport {

    private val json = AppJson.Pretty

    fun toJson(entries: List<ToolAuditEntry>, verified: Boolean, generatedAt: Long): String {
        val root = buildJsonObject {
            put("generatedAt", generatedAt)
            put("count", entries.size)
            put("verified", verified)
            putJsonArray("entries") {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("id", entry.id)
                            put("at", entry.at)
                            put("chatId", entry.chatId?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("tool", entry.tool)
                            put("argsPreview", entry.argsPreview)
                            put("success", entry.success)
                            put("resultHash", entry.resultHash)
                            put("durationMs", entry.durationMs)
                            put("model", entry.model?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("prevHash", entry.prevHash)
                            put("hash", entry.hash)
                        }
                    )
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private const val CSV_HEADER =
        "id,at,chatId,tool,argsPreview,success,resultHash,durationMs,model,prevHash,hash"

    fun toCsv(entries: List<ToolAuditEntry>): String = buildString {
        appendLine(CSV_HEADER)
        entries.forEach { entry ->
            appendLine(
                listOf(
                    entry.id,
                    entry.at,
                    entry.chatId ?: "",
                    entry.tool,
                    entry.argsPreview,
                    if (entry.success) "1" else "0",
                    entry.resultHash,
                    entry.durationMs,
                    entry.model ?: "",
                    entry.prevHash,
                    entry.hash
                ).joinToString(",") { csvField(it.toString()) }
            )
        }
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
