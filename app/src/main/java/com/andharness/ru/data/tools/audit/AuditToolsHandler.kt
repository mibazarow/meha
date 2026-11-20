package com.andharness.ru.data.tools.audit

import android.content.Context
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.formatTimestamp
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.audit.ToolAuditExport
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.tools.ToolHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The `audit_export` tool (update.md «Идеи следующих волн», idea #6): writes the whole append-only
 * tool-call audit log to a user-visible file and reports the path plus the chain-verification
 * result. No special permission is required: paths resolve against the effective tool root
 * (public storage when "All files access" is granted, otherwise the app-private dir), matching the
 * existing file-tool conventions in [com.andharness.ru.data.tools.ToolUtils].
 *
 * The format follows the file extension: `*.csv` → CSV, anything else → JSON.
 */
class AuditToolsHandler(
    @ApplicationContext private val context: Context,
    private val audit: ToolAuditRepository
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "audit_export"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val entries = audit.all()
                    if (entries.isEmpty()) {
                        return@withContext ToolResult(
                            true,
                            "Журнал аудита пуст: вызовов инструментов ещё не было."
                        )
                    }
                    val verification = audit.verify()
                    val now = System.currentTimeMillis()
                    val requested = arguments.str("path")
                    val csv = requested?.trim()?.lowercase()?.endsWith(".csv") == true
                    val target = resolveTarget(requested, csv, now)

                    val payload = if (csv) {
                        ToolAuditExport.toCsv(entries)
                    } else {
                        ToolAuditExport.toJson(entries, verification.ok, now)
                    }
                    target.parentFile?.mkdirs()
                    target.writeText(payload, Charsets.UTF_8)

                    val chain = if (verification.ok) {
                        "цепочка целостна, проверено записей: ${verification.checked}"
                    } else {
                        "ВНИМАНИЕ: целостность нарушена на записи #${verification.firstBrokenIndex} " +
                            "(${verification.reason})"
                    }
                    ToolResult(
                        true,
                        "Журнал аудита экспортирован: ${target.absolutePath} " +
                            "(записей: ${entries.size}; $chain)."
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка экспорта журнала аудита: ${e.message}")
                }
            }
        }
    )

    /** Default name carries a timestamp so repeated exports never overwrite each other. */
    private fun resolveTarget(raw: String?, csv: Boolean, now: Long): File {
        val fallback = "Documents/andharness_audit_" +
            formatTimestamp(now, "yyyyMMdd_HHmmss") + if (csv) ".csv" else ".json"
        return resolveSafePath(context.allowedToolRoots(), raw ?: fallback)
    }
}
