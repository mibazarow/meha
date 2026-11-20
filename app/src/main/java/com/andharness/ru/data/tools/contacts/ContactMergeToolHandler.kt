package com.andharness.ru.data.tools.contacts

import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_merge`: keeps two contacts together via an aggregation rule. */
internal class ContactMergeToolHandler(
    private val applier: ContactAggregationApplier,
) : ToolHandler {
    override val name = "contact_merge"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id1 = (arguments["contact_id1"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val id2 = (arguments["contact_id2"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (id1.isEmpty() || id2.isEmpty()) {
                return@withContext ToolResult(false, "Укажите contact_id1 и contact_id2.")
            }
            applier.apply(id1, id2, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение контактов (нужен WRITE_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
