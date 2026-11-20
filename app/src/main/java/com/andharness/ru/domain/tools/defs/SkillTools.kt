package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- App skills (M2: record once, replay with parameters) ----
internal object SkillTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "skill_list",
            description = "Список сохранённых навыков приложений: id, имя, приложение, число параметров и " +
                "шагов. Навык — это повторяемый сценарий работы в приложении с параметрами (например " +
                "«Заказать такси» с параметром {адрес}), который можно переигрывать. Только чтение; " +
                "создание и правка — skill_save, подробности — skill_get.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "skill_get",
            description = "Показать определение навыка целиком (JSON) по id или name. Передай id " +
                "(предпочтительно) или name. Используй, чтобы проверить шаги и параметры перед " +
                "skill_save. Только чтение.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id навыка из skill_list") }
                    putJsonObject("name") { put("type", "string"); put("description", "Имя навыка") }
                }
            },
        ),
        Tool(
            name = "skill_save",
            description = "Создать или обновить навык приложения. Обязательно name; id — для обновления " +
                "существующего (иначе обновление идёт по совпадению name). " +
                "steps — СТРОКА с JSON-массивом шагов; каждый шаг имеет поле type:\n" +
                "{\"type\":\"launch_app\",\"packageName\":\"com.app\"}\n" +
                "{\"type\":\"tap\",\"selector\":{...}}\n" +
                "{\"type\":\"type\",\"selector\":{...},\"textTemplate\":\"текст {параметр}\"}\n" +
                "{\"type\":\"wait_for_text\",\"selector\":{...},\"timeoutMs\":5000}\n" +
                "{\"type\":\"scroll\",\"selector\":{...},\"direction\":\"DOWN|UP|LEFT|RIGHT\",\"amount\":1}\n" +
                "{\"type\":\"key\",\"keyName\":\"BACK|HOME|RECENTS|ENTER\"}\n" +
                "{\"type\":\"delay\",\"ms\":500}\n" +
                "{\"type\":\"assert_text\",\"selector\":{...},\"expected\":\"Оплачено\"}\n" +
                "{\"type\":\"open_url\",\"urlTemplate\":\"https://site/{запрос}\"}\n" +
                "selector — объект с полями text, textContains, resourceId, contentDescription, " +
                "className, index (число, необязательно) и description (человеческая подпись); нужен " +
                "хотя бы один опознающий признак (text/textContains/resourceId/contentDescription). " +
                "parameters — СТРОКА с JSON-массивом: " +
                "{\"name\":\"адрес\",\"type\":\"TEXT|NUMBER|CHOICE\",\"description\":\"...\"," +
                "\"required\":true,\"default\":null,\"choices\":[]}. " +
                "В textTemplate/urlTemplate/expected поддерживается подстановка {имя_параметра}. " +
                "Неверный JSON или неизвестный type шага возвращает честную ошибку с номером шага — " +
                "навык не сохраняется. " +
                "Создавай навык только по просьбе пользователя или для явно повторяющегося сценария; " +
                "для разовых действий не создавай; сначала проверь существующий через skill_list.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id существующего навыка для обновления (необязательно)") }
                    putJsonObject("name") { put("type", "string"); put("description", "Имя навыка, например «Заказать такси»") }
                    putJsonObject("description") { put("type", "string"); put("description", "Что делает навык (необязательно)") }
                    putJsonObject("appPackage") { put("type", "string"); put("description", "Имя пакета целевого приложения, например com.app (необязательно)") }
                    putJsonObject("steps") { put("type", "string"); put("description", "JSON-массив шагов (см. описание)") }
                    putJsonObject("parameters") { put("type", "string"); put("description", "JSON-массив параметров (см. описание, необязательно)") }
                }
                putJsonArray("required") { add(JsonPrimitive("name")) }
            },
        ),
        Tool(
            name = "skill_delete",
            description = "Удалить сохранённый навык по id или name. Действие необратимо и выполняется " +
                "сразу, без подтверждения: вызывай только по прямой просьбе пользователя. Если навык " +
                "не найден — честная ошибка, ничего не удаляется.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id навыка (необязательно)") }
                    putJsonObject("name") { put("type", "string"); put("description", "Имя навыка") }
                }
            },
        ),
        Tool(
            name = "skill_run",
            description = "Запустить сохранённый навык: выполнить его шаги в установленных приложениях " +
                "через службу спецвозможностей (запуск приложения, нажатия, ввод текста, ожидание текста, " +
                "прокрутка, системные клавиши, паузы, проверки и открытие ссылок). Передай id " +
                "(предпочтительно) или name, а в values — JSON-объект со значениями параметров " +
                "{\"параметр\":\"значение\"}; обязательные параметры без значения дают честный отказ. " +
                "dry_run=true только проверяет план (селекторы и подстановку параметров) и ничего не " +
                "нажимает. Возвращает пошаговый честный отчёт (номер шага, действие, результат); при " +
                "ошибке шага запуск прерывается. Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id навыка из skill_list") }
                    putJsonObject("name") { put("type", "string"); put("description", "Имя навыка") }
                    putJsonObject("values") { put("type", "string"); put("description", "JSON-объект значений параметров, например {\"адрес\":\"Ленина 1\"} (необязательно)") }
                    putJsonObject("dry_run") { put("type", "boolean"); put("description", "true — только проверить план, не трогая экран (по умолчанию false)") }
                }
            },
        ),
        Tool(
            name = "skill_record_start",
            description = "Начать запись навыка: дальнейшие действия ui_tap, ui_type, ui_scroll, key_event " +
                "записываются вместе с семантикой элементов (id/описание/текст), чтобы потом превратить их " +
                "в устойчивые шаги. appPackage — необязательная пометка приложения; если не указана, " +
                "берётся приложение в активном окне. Остановить и сохранить — skill_record_stop.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("appPackage") { put("type", "string"); put("description", "Имя пакета целевого приложения, например com.app (необязательно)") }
                }
            },
        ),
        Tool(
            name = "skill_record_stop",
            description = "Остановить запись навыка и превратить её в готовый навык: name — имя навыка " +
                "(обязательно), description и appPackage — необязательно. Действия конвертируются в шаги по " +
                "семантике (resourceId/contentDescription/text); то, что нельзя выразить без координат " +
                "(свайп, мультитач, тап по x/y), честно пропускается с пояснением. Возвращает id навыка и " +
                "краткий отчёт: сколько шагов создано и что пропущено.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string"); put("description", "Имя навыка, например «Заказать такси»") }
                    putJsonObject("description") { put("type", "string"); put("description", "Что делает навык (необязательно)") }
                    putJsonObject("appPackage") { put("type", "string"); put("description", "Имя пакета целевого приложения (необязательно)") }
                }
                putJsonArray("required") { add(JsonPrimitive("name")) }
            },
        ),
        Tool(
            name = "skill_export",
            description = "Выгрузить навык(и) в JSON (точный формат SkillCodec) для шаринга или бэкапа " +
                "ТОЛЬКО навыков (чаты и память не входят). Укажи id или name одного навыка, либо all=true " +
                "для всех. Только чтение.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id навыка из skill_list") }
                    putJsonObject("name") { put("type", "string"); put("description", "Имя навыка") }
                    putJsonObject("all") { put("type", "boolean"); put("description", "true — выгрузить все навыки (по умолчанию false)") }
                }
            },
        ),
        Tool(
            name = "skill_import",
            description = "Импортировать навык(и) из JSON (формат skill_export): один объект или массив. " +
                "Каждый навык проверяется (id, name, шаги/селекторы); отчёт честный по каждому — импортирован, " +
                "пропущен или ошибка. Существующий навык (по id или имени) пропускается, пока overwrite=true.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("json") { put("type", "string"); put("description", "JSON навыка или массива навыков (формат skill_export)") }
                    putJsonObject("overwrite") { put("type", "boolean"); put("description", "true — заменять существующие навыки (по умолчанию false)") }
                }
                putJsonArray("required") { add(JsonPrimitive("json")) }
            },
        ),

        Tool(
            name = "fgs_start",
            description = "Поднять foreground-сервис с явным типом. type: dataSync | specialUse | shortService. " +
                "Сервис сам ничего не делает — он удерживает процесс в foreground. shortService живёт не дольше " +
                "~3 минут и останавливается сам (через 2,5 минуты); dataSync требует FOREGROUND_SERVICE_DATA_SYNC, " +
                "specialUse — FOREGROUND_SERVICE_SPECIAL_USE (оба объявлены). Запуск из фона на Android 12+ запрещён " +
                "и вернёт честную ошибку. Остановить — fgs_stop.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Тип foreground-сервиса: dataSync | specialUse | shortService")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("type")) }
            },
        ),
        Tool(
            name = "fgs_stop",
            description = "Остановить foreground-сервис, запущенный через fgs_start / keep_alive_start, снять " +
                "уведомление и проверить, что он действительно остановился. Если сервис не запущен — честно " +
                "сообщает, что останавливать нечего.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "keep_alive_start",
            description = "Удерживать процесс живым: foreground-сервис (specialUse) + PARTIAL_WAKE_LOCK. label — " +
                "необязательная метка блокировки. Wake lock берётся с жёстким ограничением 1 час и будет " +
                "освобождён системой автоматически. Повышает расход батареи (CPU не уходит в сон). Если процесс " +
                "будет убит системой, сервис НЕ перезапустится сам. Остановить — keep_alive_stop.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("label") {
                        put("type", "string")
                        put("description", "Необязательная метка wake lock (например имя задачи)")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "keep_alive_stop",
            description = "Освободить PARTIAL_WAKE_LOCK и остановить foreground-сервис, запущенный keep_alive_start, " +
                "затем проверить реальное состояние (освобождена ли блокировка, остановлен ли сервис).",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
    )
}
