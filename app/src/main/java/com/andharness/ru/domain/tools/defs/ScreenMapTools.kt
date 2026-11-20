package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ---- Screen map (per-app knowledge cache, checklist §1 «Знание экранов») ----
internal object ScreenMapTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "screen_map_get",
            description = "Показать карту экранов — то, что агент уже видел в приложениях (КЭШ). " +
                "Без app_package возвращает сводку по известным приложениям; с app_package — запомненные " +
                "экраны этого приложения: название, подпись, когда видели в последний раз и заметные " +
                "интерактивные элементы. Карта может устареть: перед действием обязательно проверь живой " +
                "экран через ui_tree. Только чтение.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("app_package") { put("type", "string"); put("description", "Имя пакета приложения, например com.android.chrome (необязательно; без него — сводка по всем известным приложениям)") }
                }
            },
        ),
        Tool(
            name = "screen_map_list",
            description = "Краткий список приложений в карте экранов: сколько экранов запомнено и когда " +
                "видели в последний раз. Это КЭШ того, что агент уже видел, и он может устареть; перед " +
                "действием проверь живой экран через ui_tree. Только чтение.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "screen_map_forget",
            description = "Забыть карту экранов: с app_package — только это приложение, без него — всю " +
                "карту. Карта — это кэш увиденного, поэтому очистка ничего не ломает: при следующей " +
                "работе в приложении она наполнится заново.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("app_package") { put("type", "string"); put("description", "Имя пакета приложения (необязательно; без него очищается вся карта)") }
                }
            },
        ),
    )
}
