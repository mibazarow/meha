package com.andharness.ru.data.tools.contacts

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** `contact_birthdays`: upcoming birthdays within a day window. */
internal class ContactBirthdaysToolHandler(
    private val io: ContactsProviderIo,
) : ToolHandler {
    override val name = "contact_birthdays"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val daysRaw = (arguments["days"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val days = if (daysRaw.isEmpty()) {
                30
            } else {
                daysRaw.toIntOrNull()
                    ?: return@withContext ToolResult(false, "Параметр days должен быть целым числом.")
            }
            if (days < 0) {
                return@withContext ToolResult(false, "Параметр days не может быть отрицательным.")
            }
            // Birthdays are Event rows with TYPE=TYPE_BIRTHDAY and START_DATE in data1.
            // https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Event
            val upcoming = ContactBirthdays.upcoming(
                entries = io.birthdayEntries(),
                today = LocalDate.now(),
                windowDays = days,
            )
            if (upcoming.isEmpty()) {
                ToolResult(true, "Ближайших дней рождения в окне $days дн. нет.")
            } else {
                val lines = upcoming.map { item ->
                    val whenText = if (item.daysUntil == 0) {
                        "сегодня"
                    } else {
                        "через ${item.daysUntil} дн."
                    }
                    val date = ContactBirthdays.formatMonthDay(item.month, item.day)
                    "${item.contactId} | ${item.name} | $date ($whenText)"
                }
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение контактов (нужен READ_CONTACTS).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
