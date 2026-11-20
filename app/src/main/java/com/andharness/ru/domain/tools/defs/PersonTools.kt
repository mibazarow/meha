package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §4.5: person resolution + personal-base tools ----
internal object PersonTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "person_find",
            description = "Найти персону в единой персональной базе («какой Серёга»). query — имя, " +
                "алиас, телефон, email или ник; необязательный channel (SMS, CALL, MESSENGER, EMAIL, " +
                "CHAT) подсказывает предпочтительный канал. Сначала выполняется точное совпадение " +
                "нормализованной идентичности, затем нечёткое совпадение имени/алиасов (опечатки, " +
                "уменьшительные, транслит), затем скоринг с учётом канала, недавности и частоты " +
                "взаимодействий. Возвращает ранжированных кандидатов (#id, имя, идентичности, " +
                "последнее взаимодействие, score). Если результат неоднозначен — перечисляет " +
                "кандидатов и просит пользователя выбрать, а не угадывает. Используй результат " +
                "вместо повторного сканирования телефона.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put(
                            "description",
                            "Что искать: имя, алиас, телефон, email или ник (обязательно)."
                        )
                    }
                    putJsonObject("channel") {
                        put("type", "string")
                        put(
                            "description",
                            "Необязательный канал, под который подбираем: SMS, CALL, MESSENGER, EMAIL, " +
                                "CHAT. Пример: для SMS предпочитается PHONE-идентичность."
                        )
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "person_get",
            description = "Профиль персоны из персональной базы: имя, заметки, алиасы, все " +
                "идентичности (телефон/email/мессенджеры) и последние взаимодействия. Хранятся и " +
                "показываются только метаданные и краткое резюме (gist ≤ 80 символов) — тело " +
                "сообщения недоступно.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("person_id") {
                        put("type", "string")
                        put("description", "Числовой id персоны, например 12 (обязательно).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put(
                            "description",
                            "Сколько последних взаимодействий показать, 1–100 (по умолчанию 10)."
                        )
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("person_id")) }
            },
        ),
        Tool(
            name = "person_link",
            description = "Привязать идентичность (телефон, email, ник мессенджера или иной хендл) к " +
                "персоне. type — PHONE, EMAIL, MESSENGER или OTHER; value — сам хендл; необязательные " +
                "package (пакет приложения для мессенджера) и label (подпись). Значение нормализуется. " +
                "После записи выполняется пост-проверка. Если такой хендл уже принадлежит другой " +
                "персоне, инструмент честно сообщает конфликт и ничего не меняет (вариант — " +
                "person_merge).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("person_id") {
                        put("type", "string")
                        put("description", "Числовой id персоны, к которой привязываем (обязательно).")
                    }
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Тип идентичности: PHONE, EMAIL, MESSENGER или OTHER.")
                    }
                    putJsonObject("value") {
                        put("type", "string")
                        put("description", "Значение хендла: номер телефона, email или ник.")
                    }
                    putJsonObject("package") {
                        put("type", "string")
                        put(
                            "description",
                            "Необязательный пакет приложения для MESSENGER (один и тот же ник в разных " +
                                "приложениях — разные идентичности)."
                        )
                    }
                    putJsonObject("label") {
                        put("type", "string")
                        put("description", "Необязательная подпись, например «рабочий».")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("person_id"))
                    add(JsonPrimitive("type"))
                    add(JsonPrimitive("value"))
                }
            },
        ),
        Tool(
            name = "person_merge",
            description = "Слить дубли персон: перенести идентичности, взаимодействия и алиасы из " +
                "person_id в into_person_id, отбросить дубликаты и удалить исходную персону. " +
                "Выполняется одной транзакцией и пост-проверяется. Действие необратимо.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("person_id") {
                        put("type", "string")
                        put("description", "Числовой id исходной персоны, которую удаляем (обязательно).")
                    }
                    putJsonObject("into_person_id") {
                        put("type", "string")
                        put("description", "Числовой id персоны, в которую сливаем (обязательно).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("person_id"))
                    add(JsonPrimitive("into_person_id"))
                }
            },
        ),
        Tool(
            name = "person_alias_set",
            description = "Добавить альтернативное имя (алиас) персоне, например «Серёга» или " +
                "«Serega». По алиасам работает нечёткий резолв person_find. После записи выполняется " +
                "пост-проверка.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("person_id") {
                        put("type", "string")
                        put("description", "Числовой id персоны (обязательно).")
                    }
                    putJsonObject("alias") {
                        put("type", "string")
                        put("description", "Альтернативное имя, например «Серёга» (обязательно).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("person_id"))
                    add(JsonPrimitive("alias"))
                }
            },
        ),
        Tool(
            name = "interaction_search",
            description = "История взаимодействий с человеком (SMS, звонки, мессенджеры, почта, " +
                "чаты) в персональной базе, новые сверху. Вызывай, когда вопрос касается конкретного " +
                "человека («когда мы последний раз…», «что он писал»). Можно задать query " +
                "(подстрока по метаданным и краткому резюме), person_id (только по одной персоне) " +
                "или оба. Хранятся и показываются только метаданные и gist ≤ 80 символов — тела " +
                "сообщений недоступны; для полного текста используй message_search.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Необязательная подстрока для поиска по метаданным/резюме.")
                    }
                    putJsonObject("person_id") {
                        put("type", "string")
                        put("description", "Необязательный числовой id персоны.")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько записей вернуть, 1–200 (по умолчанию 30).")
                    }
                }
            },
        ),
    )
}
