package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- notes / memory / todos ----
internal object NotesMemoryTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "note_create",
            description = "Сохранить заметку.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("text") { put("type", "string"); put("description", "Текст заметки") } }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "note_list",
            description = "Показать все заметки.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "note_delete",
            description = "Удалить заметку по id или по подстроке текста (query).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id из note_list") }
                    putJsonObject("query") { put("type", "string"); put("description", "Подстрока текста заметки") }
                }
            },
        ),
        Tool(
            name = "memory_save",
            description = "Запомнить факт/предпочтение надолго (долговременная память между сессиями). Укажи tags — ключевые слова и синонимы для поиска, и importance (0 обычное, 1 важное, 2 критичное).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Что запомнить, одной фразой") }
                    putJsonObject("tags") { put("type", "string"); put("description", "Ключевые слова/синонимы через запятую") }
                    putJsonObject("importance") { put("type", "string"); put("description", "0 | 1 | 2") }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "memory_search",
            description = "Вспомнить из долговременной памяти ПО СМЫСЛУ. Вызывай ПЕРВОЙ при любом вопросе о прошлом, привычках, предпочтениях, людях, адресах, договорённостях или «что мы обсуждали», если нужного факта нет прямо в контексте — не отвечай по догадке и не заменяй это memory_list/поиском по файлам. Ищет по тексту и тегам с учётом синонимов и словоформ (напр. «налоговая» найдёт запись про ФНС), ранжирует по релевантности и важности и возвращает оценку релевантности каждой записи.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("query") { put("type", "string"); put("description", "Что искать") } }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "memory_list",
            description = "Показать сохранённую долговременную память (id, важность, теги, текст) — так можно найти id для memory_delete. Все фильтры необязательны: query — подстрока текста (без учёта регистра), tag — подстрока в тегах (без учёта регистра), kind — вид записей (по умолчанию memory), sinceDays — только созданные/обновлённые за последние N дней, limit — сколько показать (по умолчанию 30, максимум 100), orderBy — recent (свежие, по умолчанию) или importance (важные).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") { put("type", "string"); put("description", "Сколько записей показать (по умолчанию 30, максимум 100)") }
                    putJsonObject("query") { put("type", "string"); put("description", "Подстрока текста памяти (без учёта регистра)") }
                    putJsonObject("tag") { put("type", "string"); put("description", "Подстрока в тегах памяти (без учёта регистра)") }
                    putJsonObject("kind") { put("type", "string"); put("description", "Вид записей: memory (по умолчанию) или note") }
                    putJsonObject("sinceDays") { put("type", "string"); put("description", "Только созданные/обновлённые за последние N дней") }
                    putJsonObject("orderBy") { put("type", "string"); put("description", "recent (по умолчанию) | importance") }
                }
            },
        ),
        Tool(
            name = "memory_delete",
            description = "Удалить запись долговременной памяти по id (id из memory_list или memory_search). Удаляет только память, заметки не трогает.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("id") { put("type", "string"); put("description", "id записи памяти") } }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "profile_list",
            description = "Показать сохранённый ПРОФИЛЬ пользователя — долговременные факты о нём самом (имя, устойчивые предпочтения, важный постоянный контекст), которые он явно попросил запомнить. Этот профиль общий для ВСЕХ чатов и всегда подставляется в контекст. Только чтение, ничего не меняет.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "profile_set",
            description = "Сохранить или обновить ОДИН долговременный факт в общем профиле пользователя (общий для всех чатов). text — сам факт одной фразой (обязательно); id — id существующей записи из profile_list, если нужно её изменить. Только для того, что пользователь явно просит помнить между чатами (имя, постоянные предпочтения, важный повторяющийся контекст); НЕ для разовых деталей — для них memory_save. Одинаковый текст повторно не дублируется, слишком длинный или пустой текст отклоняется честно.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Факт одной фразой (обязательно)") }
                    putJsonObject("id") { put("type", "string"); put("description", "id существующей записи из profile_list, чтобы изменить её") }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "profile_forget",
            description = "Удалить факт из общего профиля пользователя: id — удалить одну запись (id из profile_list), либо all=true — очистить профиль целиком. Необратимо.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string"); put("description", "id записи из profile_list") }
                    putJsonObject("all") { put("type", "boolean"); put("description", "true — удалить все факты профиля") }
                }
            },
        ),
        Tool(
            name = "opinion_set",
            description = "Сформировать мнение (позицию) с уверенностью. Уверенность 0..1 со временем затухает (половина за 90 дней), поэтому свежее мнение весит больше старого. Если это мнение заменяет прежнее — укажи supersedesId (id старого мнения из opinion_list): старое сохранится в истории, но перестанет вспоминаться.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Само мнение/позиция одной фразой") }
                    putJsonObject("confidence") { put("type", "string"); put("description", "Уверенность 0..1 (по умолчанию 0.8)") }
                    putJsonObject("supersedesId") { put("type", "string"); put("description", "id прежнего мнения, которое это заменяет (0 = никого)") }
                    putJsonObject("tags") { put("type", "string"); put("description", "Ключевые слова/синонимы через запятую") }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "opinion_list",
            description = "Показать мнения (id, эффективная уверенность с учётом затухания, текст); вытесненные новыми мнениями по умолчанию скрыты. limit — сколько показать (по умолчанию 20, максимум 50), includeRetired — показать и вытесненные (по умолчанию false).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") { put("type", "string"); put("description", "Сколько мнений показать (по умолчанию 20, максимум 50)") }
                    putJsonObject("includeRetired") { put("type", "boolean"); put("description", "Показать и вытесненные мнения (по умолчанию false)") }
                }
            },
        ),
        Tool(
            name = "todo_add",
            description = "Добавить задачу в список дел.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("text") { put("type", "string"); put("description", "Текст задачи") } }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "todo_list",
            description = "Показать список дел (выполненные помечены [x]).",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "todo_done",
            description = "Отметить задачу выполненной по id (из todo_list).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("id") { put("type", "string"); put("description", "id задачи") } }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "todo_delete",
            description = "Удалить задачу из списка дел по id.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("id") { put("type", "string"); put("description", "id задачи") } }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
    )
}
