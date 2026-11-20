package com.andharness.ru.data.tools.autofill

import android.content.Context
import android.view.autofill.AutofillManager
import com.andharness.ru.data.autofill.AutofillField
import com.andharness.ru.data.autofill.AutofillProfile
import com.andharness.ru.data.autofill.AutofillProfileStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the two owner-profile tools of the minimal autofill feature (backlog #15):
 *
 * - `autofill_profile_set` — stores the user's OWN name / phone / e-mail;
 * - `autofill_profile_get` — shows the stored profile and whether ANDharness is the enabled
 *   autofill service.
 *
 * Honesty notes: the profile is stored locally in `SharedPreferences` and is never uploaded; the
 * service only ever fills these three self-describing values and never captures data typed into
 * other apps (`onSaveRequest` is a documented no-op). Clearing: an explicitly empty string clears
 * one field, `clear=true` clears the whole profile, and an omitted parameter leaves the field as it
 * was. The system autofill picker is reached through `Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE`.
 *
 * References:
 * - https://developer.android.com/reference/android/view/autofill/AutofillManager#hasEnabledAutofillServices()
 * - https://developer.android.com/identity/autofill/autofill-services
 */
class AutofillToolsHandler(
    private val context: Context,
) {

    private val store = AutofillProfileStore(context)

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "autofill_profile_set"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        if (boolArg(arguments, "clear")) {
                            store.clear()
                            return@withContext ToolResult(
                                true,
                                "Профиль автозаполнения очищен целиком — сервис больше не будет " +
                                    "предлагать данные. ${serviceStatus()}"
                            )
                        }
                        val nameArg = stringArg(arguments, "name")
                        val phoneArg = stringArg(arguments, "phone")
                        val emailArg = stringArg(arguments, "email")
                        if (nameArg == null && phoneArg == null && emailArg == null) {
                            return@withContext ToolResult(
                                false,
                                "Укажи хотя бы одно поле: name, phone или email. Пустая строка " +
                                    "очищает именно это поле, clear=true очищает весь профиль."
                            )
                        }
                        val current = store.load()
                        val updated = AutofillProfile(
                            fullName = resolve(nameArg, current.fullName),
                            phone = resolve(phoneArg, current.phone),
                            email = resolve(emailArg, current.email),
                        )
                        store.save(updated)
                        ToolResult(true, describe(updated) + "\n" + serviceStatus())
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
        object : ToolHandler {
            override val name = "autofill_profile_get"
            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        val profile = store.load()
                        val body = if (profile.isEmpty) {
                            "Профиль автозаполнения пуст. Заполни его через autofill_profile_set " +
                                "(name, phone, email)."
                        } else {
                            describe(profile)
                        }
                        ToolResult(true, "$body\n${serviceStatus()}")
                    } catch (e: Exception) {
                        ToolResult(false, "Ошибка: ${e.message}")
                    }
                }
        },
    )

    /**
     * null = argument absent (keep the current value); blank = explicit clear (drop the field);
     * otherwise the trimmed new value.
     */
    private fun resolve(argument: String?, current: String?): String? = when {
        argument == null -> current
        argument.isBlank() -> null
        else -> argument.trim()
    }

    private fun describe(profile: AutofillProfile): String = buildString {
        appendLine("Профиль автозаполнения (заполняет только ваши собственные данные):")
        appendLine("• имя: ${profile.valueFor(AutofillField.NAME) ?: "—"}")
        appendLine("• телефон: ${profile.valueFor(AutofillField.PHONE) ?: "—"}")
        append("• e-mail: ${profile.valueFor(AutofillField.EMAIL) ?: "—"}")
    }

    /** Honest report of whether the OS currently uses ANDharness as its autofill service. */
    private fun serviceStatus(): String = try {
        val manager = context.getSystemService(AutofillManager::class.java)
        when {
            manager == null -> "Система не поддерживает автозаполнение."
            !manager.isAutofillSupported -> "Устройство не поддерживает автозаполнение."
            manager.hasEnabledAutofillServices() ->
                "Меха включён как сервис автозаполнения."
            else -> "Меха пока НЕ выбран сервисом автозаполнения: включите его в настройках " +
                "Android (Система → Языки и ввод → Автозаполнение)."
        }
    } catch (e: Exception) {
        "Статус сервиса автозаполнения недоступен: ${e.message}"
    }

    /** Content of [key] when present as a JSON primitive; null when absent. */
    private fun stringArg(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.content

    /** Accepts both a real JSON boolean and the string forms the local model tends to send. */
    private fun boolArg(arguments: JsonObject, key: String): Boolean {
        val raw = (arguments[key] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        return raw == "true" || raw == "1" || raw == "yes" || raw == "да"
    }
}
