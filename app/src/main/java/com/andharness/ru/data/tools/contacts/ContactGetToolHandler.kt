package com.andharness.ru.data.tools.contacts

import android.provider.ContactsContract
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `contact_get`: name, phones and emails of one aggregate contact. */
internal class ContactGetToolHandler(
    private val io: ContactsProviderIo,
) : ToolHandler {
    override val name = "contact_get"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val idArg = arguments["id"] as? JsonPrimitive
            if (idArg == null) {
                return@withContext ToolResult(false, "Укажите id.")
            }
            val id = idArg.content.trim()
            // Name comes from the aggregate Contacts row so email-only (phone-less)
            // contacts still resolve; phones and emails are read from the joined Data
            // views by CONTACT_ID (bounded, cursors closed). Email.CONTENT_URI exactly
            // mirrors Phone.CONTENT_URI. Reference:
            // https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Email
            val name = io.contactDisplayName(id)
            val phones = io.dataValuesForContact(
                id,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val emails = io.dataValuesForContact(
                id,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            )
            if (name == null && phones.isEmpty() && emails.isEmpty()) {
                return@withContext ToolResult(true, "Контакт не найден.")
            }
            val displayName = name?.takeIf { it.isNotBlank() } ?: "(без имени)"
            val lines = ArrayList<String>()
            for (number in phones) lines.add("$displayName | $number")
            for (email in emails) lines.add("$displayName | email: $email")
            if (lines.isEmpty()) lines.add("$displayName | (нет телефонов и email)")
            ToolResult(true, lines.joinToString("\n"))
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение контактов (нужен READ_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
