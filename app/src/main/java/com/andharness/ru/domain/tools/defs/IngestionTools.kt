package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- update.md §4: personal base ingestion ----
internal object IngestionTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "messaging_index_config",
            description = "Настройка инкрементального индексатора персональной базы (люди и их " +
                "взаимодействия): opt-in, список пакетов мессенджеров/почты и состояние разового " +
                "бэкфилла (контакты, SMS, журнал вызовов). action=get — показать реальное состояние " +
                "(флаг, пакеты, спец-доступ Notification Listener, разрешения источников, курсоры); " +
                "action=set — применить enabled и (необязательно) packages. При включении ставится " +
                "bounded бэкфилл, при выключении инкрементальный приём останавливается. Честно: " +
                "индексация уведомлений требует спец-доступа Notification Listener, ничего не " +
                "индексируется, пока opt-in выключен. Метаданные и краткое резюме (≤80 символов) " +
                "пишутся в interactions, а ПОЛНЫЙ текст сообщений сохраняется в шифрованной базе " +
                "приложения (stored_messages) для поиска и сводок — данные остаются только на " +
                "устройстве и не логируются.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put(
                            "description",
                            "get — показать состояние (по умолчанию); set — применить enabled/packages."
                        )
                    }
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Для action=set: true — включить индексацию и запустить бэкфилл; false — выключить."
                        )
                    }
                    putJsonObject("packages") {
                        put("type", "string")
                        put(
                            "description",
                            "Для action=set: пакеты мессенджеров/почты через запятую. Пусто — вернуть " +
                                "список по умолчанию."
                        )
                    }
                }
            },
        ),
        Tool(
            name = "messaging_index_app_add",
            description = "Добавить установленное приложение в список индексации персональной базы " +
                "по названию или пакету: app — название (например «MAX», «Telegram») или пакет " +
                "(org.telegram.messenger). Инструмент находит установленное приложение, добавляет " +
                "его пакет в список мессенджеров/почты, включает индексацию и ставит разовый " +
                "бэкфилл (контакты, SMS, журнал вызовов). Если по запросу найдено несколько " +
                "приложений или ни одного — ничего не меняет, перечисляет кандидатов и просит " +
                "указать точный пакет: не угадывает. Честно сообщает реальное состояние индексации, " +
                "спец-доступ Notification Listener и разрешения источников.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("app") {
                        put("type", "string")
                        put(
                            "description",
                            "Название приложения («MAX», «Telegram») или его пакет " +
                                "(org.telegram.messenger), обязательно."
                        )
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("app")) }
            },
        ),
        Tool(
            name = "messaging_index_app_remove",
            description = "Убрать приложение из списка индексации персональной базы по названию или " +
                "пакету: app — название или пакет. Инструмент находит установленное приложение и " +
                "убирает его пакет из списка мессенджеров/почты; индексация при этом не выключается. " +
                "Честно: если удалён последний сохранённый пакет, хранилище возвращается к списку по " +
                "умолчанию (MessagingIndexPolicy.DEFAULT_PACKAGES), то есть фактически снова действует " +
                "список по умолчанию. Если найдено несколько приложений или ни одного — ничего не " +
                "меняет, перечисляет кандидатов и просит указать точный пакет: не угадывает.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("app") {
                        put("type", "string")
                        put(
                            "description",
                            "Название приложения («MAX», «Telegram») или его пакет " +
                                "(org.telegram.messenger), обязательно."
                        )
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("app")) }
            },
        ),
    )
}
