package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- UI macros (record / replay with self-check) ----
internal object UiMacroTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "ui_macro_start",
            description = "Начать запись UI-макроса: дальнейшие действия ui_tap, ui_long_press, ui_swipe, " +
                "ui_scroll, ui_type, key_event и ui_gesture_multi записываются по порядку. name — имя " +
                "макроса (обязательно). Предыдущая незавершённая запись отменяется. Служба " +
                "спецвозможностей не требуется для самой записи, но без неё действия не выполняются.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string"); put("description", "Имя макроса") }
                }
                putJsonArray("required") { add(JsonPrimitive("name")) }
            },
        ),
        Tool(
            name = "ui_macro_stop",
            description = "Остановить запись UI-макроса и сохранить записанные шаги под именем из " +
                "ui_macro_start. Возвращает id и число шагов. Если запись не активна или не записано " +
                "ни одного шага — честная ошибка.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "ui_macro_list",
            description = "Список сохранённых UI-макросов: id, имя, число шагов.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "ui_macro_run",
            description = "Воспроизвести сохранённый UI-макрос по id или name: шаги выполняются по порядку " +
                "с короткой паузой. После каждого шага, где есть selector (text/desc/id), проверяется, " +
                "что элемент по-прежнему находится на экране. Если служба спецвозможностей отключилась, " +
                "шаг не выполнился или проверка не прошла — воспроизведение прерывается с указанием номера " +
                "шага и ожидаемого элемента. Успех сообщается только после всех шагов.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id макроса из ui_macro_list (необязательно)") }
                    putJsonObject("name") { put("type", "string"); put("description", "Имя макроса (необязательно)") }
                }
            },
        ),
        Tool(
            name = "ui_macro_delete",
            description = "Удалить сохранённый UI-макрос по id или name.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id макроса (необязательно)") }
                    putJsonObject("name") { put("type", "string"); put("description", "Имя макроса (необязательно)") }
                }
            },
        ),
    )
}
