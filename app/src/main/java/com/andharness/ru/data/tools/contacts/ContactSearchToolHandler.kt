package com.andharness.ru.data.tools.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Upper bound on DISTINCT aggregate contacts returned by [contact_search]. */
private const val MAX_SEARCH_RESULTS = 20

/** `contact_search`: partial-name search over the aggregate Contacts table. */
internal class ContactSearchToolHandler(
    private val context: Context,
    private val io: ContactsProviderIo,
) : ToolHandler {
    override val name = "contact_search"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = ((arguments["query"] as? JsonPrimitive)?.content ?: "").trim()
            if (query.isEmpty()) {
                // Honest empty query: never fall back to listing the whole address book.
                return@withContext ToolResult(false, "Укажите query.")
            }
            // Partial-name ("type-to-filter") search over the aggregate Contacts table.
            // The provider appends the filter as a path segment and matches parts of the
            // contact name, so phone-less (e.g. email-only) contacts are found and Cyrillic
            // case folding is handled by the provider. The _ID from Contacts is the real
            // CONTACT id that contact_get/update/delete consume — unlike the Data row id.
            // Reference: ContactsContract.Contacts#CONTENT_FILTER_URI —
            // https://developer.android.com/reference/android/provider/ContactsContract.Contacts#CONTENT_FILTER_URI
            val uri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI,
                Uri.encode(query),
            )
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            )
            val idSet = LinkedHashSet<String>()
            val namesById = HashMap<String, String?>()
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (idSet.size >= MAX_SEARCH_RESULTS) break
                    val id = if (idIdx >= 0) c.getString(idIdx) else null
                    if (id.isNullOrBlank()) continue
                    // Deduplicate by contact id: the filter URI may return a contact more
                    // than once, and the cap counts DISTINCT contacts, not rows.
                    if (idSet.add(id)) {
                        namesById[id] = if (nameIdx >= 0) c.getString(nameIdx) else null
                    }
                }
            }
            val lines = ArrayList<String>()
            for (id in idSet) {
                val name = namesById[id]?.takeIf { it.isNotBlank() } ?: "(без имени)"
                val number = io.firstPhoneForContact(id) ?: "(нет телефона)"
                lines.add("$id | $name | $number")
            }
            if (lines.isEmpty()) {
                ToolResult(true, "Контакты не найдены.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение контактов (нужен READ_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
