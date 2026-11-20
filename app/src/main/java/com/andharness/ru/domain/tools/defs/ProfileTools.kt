package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- backlog #15: owner profile for the ANDharness autofill service ----
internal object ProfileTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "autofill_profile_set",
            description = "Задать профиль автозаполнения — собственные имя, телефон и e-mail " +
                "пользователя, которые Меха подставляет в формы других приложений, если " +
                "пользователь выбрал Меха сервисом автозаполнения в настройках Android. " +
                "Указывай только те поля, которые нужно изменить: пропущенный параметр остаётся " +
                "как был. Пустая строка в поле очищает именно это поле; clear=true очищает весь " +
                "профиль. Профиль хранится локально и никогда не отправляется в сеть.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Полное имя пользователя. Пустая строка очищает именно это поле.")
                    }
                    putJsonObject("phone") {
                        put("type", "string")
                        put("description", "Номер телефона пользователя. Пустая строка очищает именно это поле.")
                    }
                    putJsonObject("email") {
                        put("type", "string")
                        put("description", "E-mail пользователя. Пустая строка очищает именно это поле.")
                    }
                    putJsonObject("clear") {
                        put("type", "boolean")
                        put("description", "true — очистить весь профиль автозаполнения (имя, телефон и e-mail).")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "autofill_profile_get",
            description = "Показать профиль автозаполнения (имя, телефон, e-mail), который " +
                "Меха предлагает в формах, и честно сообщить, выбран ли Меха сервисом " +
                "автозаполнения в настройках Android.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
            },
        ),
    )
}
