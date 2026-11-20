package com.andharness.ru.data.tools.contacts

import android.content.Context
import com.andharness.ru.data.tools.files.FileToolIo
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.contacts.VCardWriter
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * `contact_export_vcf`: writes either one contact or the whole phone book to a vCard 3.0 file.
 *
 * The vCard text is produced by the pure [VCardWriter] from rows read by [ContactsProviderIo];
 * when `path` is omitted the file lands in `<filesDir>/exports/contacts-<timestamp>.vcf`. An
 * explicit `path` is funnelled through the existing file-tool sandbox ([FileToolIo.resolvePath]),
 * so it can only name a location inside the allowed storage roots. The tool reports the real,
 * verified file path and the number of exported contacts, and never claims success otherwise.
 *
 * References:
 * - `ContactsContract.Contacts.CONTENT_VCARD_URI` (the platform vCard URI this builds the content for):
 *   https://developer.android.com/reference/android/provider/ContactsContract.Contacts#CONTENT_VCARD_URI
 * - RFC 2426 (vCard 3.0): https://datatracker.ietf.org/doc/html/rfc2426
 */
internal class ContactExportVcfToolHandler(
    private val context: Context,
    private val io: ContactsProviderIo,
) : ToolHandler {

    override val name = "contact_export_vcf"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val contactId = (arguments["contact_id"] as? JsonPrimitive)?.content
                ?.trim()?.takeIf { it.isNotEmpty() }
            if (contactId != null && !io.contactExists(contactId)) {
                return@withContext ToolResult(false, "Контакт $contactId не найден.")
            }
            val cards = io.vCards(contactId)
            if (cards.isEmpty()) {
                val scope = if (contactId != null) "у контакта $contactId" else "в телефонной книге"
                return@withContext ToolResult(false, "Экспортировать нечего: $scope нет контактов для записи.")
            }
            val text = VCardWriter.write(cards)
            val file = resolveTarget((arguments["path"] as? JsonPrimitive)?.content)
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            if (!file.isFile) {
                return@withContext ToolResult(false, "Не удалось создать файл экспорта: ${file.absolutePath}")
            }
            ToolResult(
                true,
                "Экспортировано контактов: ${cards.size}. Файл: ${file.absolutePath}",
            )
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение контактов (нужен READ_CONTACTS).")
        } catch (e: IllegalArgumentException) {
            ToolResult(false, e.message ?: "Недопустимый путь.")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /** An explicit sandbox-checked `path`, or the default `<filesDir>/exports/contacts-<ts>.vcf`. */
    private fun resolveTarget(rawPath: String?): File {
        val explicit = rawPath?.trim()
        if (!explicit.isNullOrEmpty()) {
            return File(FileToolIo(context).resolvePath(explicit))
        }
        val dir = File(context.filesDir, EXPORT_DIR_NAME)
        return File(dir, "contacts-${System.currentTimeMillis()}.vcf")
    }

    private companion object {
        /** Subdirectory of `filesDir` holding default contact exports (same as chat_export). */
        const val EXPORT_DIR_NAME = "exports"
    }
}
