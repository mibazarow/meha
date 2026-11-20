package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- open / intents ----
internal object IntentTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "open_url",
            description = "Открыть веб-ссылку (URL) во ВНЕШНЕМ браузере устройства (уводит пользователя из приложения). Используй, когда пользователь просит ПОКАЗАТЬ страницу или когда нужно открыть сайт для пользователя; чтобы прочитать текст страницы — используй web_fetch.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Абсолютная http/https ссылка для открытия в браузере")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            },
        ),
        Tool(
            name = "app_details",
            description = "Открыть системный экран «О приложении» (сведения/настройки приложения) по имени пакета. На этом экране пользователь вручную может остановить приложение, очистить кэш и включить/выключить его — агенту без системных прав это недоступно.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, напр. com.android.chrome")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "open_settings",
            description = "Открыть экран системных настроек устройства. screen: main, wifi, bluetooth, location, airplane, display, sound, battery, data, nfc, accessibility, developer, apps, all_files.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("screen") {
                        put("type", "string")
                        put("description", "Какой экран открыть: main, wifi, bluetooth, location, airplane, display, sound, battery, data, nfc, accessibility, developer, apps, all_files")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("screen")) }
            },
        ),
        Tool(
            name = "notification_settings_open",
            description = "Открыть экран настроек уведомлений. С package — настройки уведомлений конкретного приложения; без package — общие настройки уведомлений (на Android 13+ экран списка приложений, шлющих уведомления).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Имя пакета приложения (необязательно); без него открываются общие настройки уведомлений")
                    }
                }
            },
        ),
        Tool(
            name = "app_notification_settings_open",
            description = "Открыть экран настроек уведомлений конкретного приложения по имени пакета (package).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Пакет приложения, напр. com.android.chrome")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "internet_panel_open",
            description = "Открыть системную панель интернет-подключений (Wi-Fi/мобильная сеть) для быстрого переключения.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "location_settings_open",
            description = "Открыть системный экран настроек геолокации.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "share_text",
            description = "Поделиться текстом через системное меню «Поделиться» (пользователь выбирает приложение).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст, которым нужно поделиться")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "dial",
            description = "Открыть номеронабиратель с уже введённым номером (звонок НЕ совершается). Для самого звонка используй call_start.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("phone") {
                        put("type", "string")
                        put("description", "Номер телефона для набора")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("phone")) }
            },
        ),
        Tool(
            name = "intent_open",
            description = "Открыть URI, ссылку или файл в другом приложении (ACTION_VIEW, при action=edit — ACTION_EDIT). Это способ открыть документ в сторонней программе — например .docx в Word или PDF в просмотрщике — либо открыть файл на редактирование. Локальный путь автоматически превращается в безопасный content-URI через FileProvider. package принудительно открывает в указанном приложении, chooser показывает системный выбор приложения, mime задаёт тип.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "URI, ссылка (scheme://...) или локальный путь к файлу")
                    }
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "Что сделать: view (по умолчанию) или edit")
                    }
                    putJsonObject("mime") {
                        put("type", "string")
                        put("description", "MIME-тип, например application/pdf (опционально)")
                    }
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Имя пакета приложения, в котором открыть (опционально)")
                    }
                    putJsonObject("chooser") {
                        put("type", "boolean")
                        put("description", "Показать системный выбор приложения (по умолчанию false)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("uri")) }
            },
        ),
        Tool(
            name = "intent_send_to",
            description = "Отправить текст и/или файл в КОНКРЕТНОЕ приложение (ACTION_SEND явно указанному package), без системного выбора приложения. Укажите text и/или path; при вложении файла mime по умолчанию */*; subject — тема сообщения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") {
                        put("type", "string")
                        put("description", "Имя пакета приложения-получателя")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст сообщения (опционально)")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к локальному файлу-вложению (опционально)")
                    }
                    putJsonObject("mime") {
                        put("type", "string")
                        put("description", "MIME-тип вложения (по умолчанию */*)")
                    }
                    putJsonObject("subject") {
                        put("type", "string")
                        put("description", "Тема сообщения (опционально)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),

        Tool(
            name = "media_audio_list",
            description = "Список аудиофайлов из медиатеки (id, название, исполнитель, длительность, путь). Можно фильтровать по query.",
            needsPermission = "android.permission.READ_MEDIA_AUDIO",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "Фильтр по названию (опционально)") }
                    putJsonObject("limit") { put("type", "string"); put("description", "Максимум записей (опционально)") }
                }
            },
        ),
        Tool(
            name = "media_video_list",
            description = "Список видеофайлов из медиатеки (id, название, длительность, путь). Можно фильтровать по query.",
            needsPermission = "android.permission.READ_MEDIA_VIDEO",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "Фильтр по названию (опционально)") }
                    putJsonObject("limit") { put("type", "string"); put("description", "Максимум записей (опционально)") }
                }
            },
        ),

        Tool(
            name = "settings_get",
            description = "Показать текущие настройки LLM агента (endpoint, model, режим размышлений). API-ключ не возвращается.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "self_config",
            description = "Изменить собственные настройки LLM из чата: model, endpoint и/или reasoning (off/low/medium/high).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("model") { put("type", "string"); put("description", "Имя модели") }
                    putJsonObject("endpoint") { put("type", "string"); put("description", "Адрес эндпоинта") }
                    putJsonObject("reasoning") { put("type", "string"); put("description", "off | low | medium | high") }
                }
            },
        ),
        Tool(
            name = "sensor_list",
            description = "Список доступных датчиков устройства.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "sensor_read",
            description = "Прочитать одно измерение датчика. type: accelerometer, gyroscope, magnetometer, light, proximity, pressure, gravity, linear_acceleration, rotation_vector, step_counter, humidity, temperature.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") { put("type", "string"); put("description", "Тип датчика, например accelerometer") }
                    putJsonObject("timeout_ms") { put("type", "string"); put("description", "Таймаут ожидания, мс (500–10000)") }
                }
                putJsonArray("required") { add(JsonPrimitive("type")) }
            },
        ),

        Tool(
            name = "app_deep_link",
            description = "Открыть ссылку/глубокую ссылку, при желании — строго в конкретном приложении (package).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") { put("type", "string"); put("description", "Ссылка (scheme://...)") }
                    putJsonObject("package") { put("type", "string"); put("description", "Пакет приложения (опционально)") }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            },
        ),
        Tool(
            name = "app_install_apk",
            description = "Открыть системный установщик APK: для локального файла (path) или для APK, " +
                "скачанного по url в кэш приложения (нужен хотя бы один из параметров). Честно: " +
                "Android не даёт установить APK молча — агент лишь открывает системный диалог, " +
                "а «Установить» нажимает пользователь; итог установки агенту неизвестен (проверьте " +
                "через app_is_installed). Если Мехе не разрешена установка неизвестных приложений, " +
                "вернётся честная ошибка и откроется нужная настройка. Требует " +
                "REQUEST_INSTALL_PACKAGES.",
            needsPermission = "android.permission.REQUEST_INSTALL_PACKAGES",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к локальному .apk (например, из app_backup_apk или download_file)")
                    }
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Ссылка на .apk: инструмент скачает его и откроет установщик (если не задан path)")
                    }
                }
            },
        ),
        Tool(
            name = "app_store_open",
            description = "Открыть страницу Google Play для приложения (package) или поиск в Google Play (query). Нужен хотя бы один из параметров. ВАЖНО и честно: установить приложение молча агент не может — он лишь открывает страницу Play, а нажать «Установить» должен пользователь; после установки агент может проверить её через app_is_installed. Если Play Store не установлен, открывается веб-версия play.google.com.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") { put("type", "string"); put("description", "Имя пакета для страницы Google Play (если не задан query)") }
                    putJsonObject("query") { put("type", "string"); put("description", "Поисковый запрос в Google Play (если не задан package)") }
                }
            },
        ),
        Tool(
            name = "app_store_search",
            description = "Открыть поиск в Google Play по запросу (query). Установить приложение молча агент не может: открывается страница Play, а нажать «Установить» должен пользователь; после установки проверьте её через app_is_installed.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "Поисковый запрос в Google Play") }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "app_suggest_for",
            description = "Подсказать, какое приложение нужно для работы с форматом/файлом, и предложить установить его. Принимает format (MIME, расширение или общее слово: pdf, docx, rar, video, image, audio, office, archive) и/или task (свободное описание: «открыть pdf», «редактировать документ Word»). Возвращает определённый MIME/семейство, установлено ли подходящее приложение (проверка resolveActivity) и, если нет, короткий список приложений из Google Play со ссылками на установку. Честно сообщает, если формат не распознан — агент не выдумывает приложения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("format") {
                        put("type", "string")
                        put("description", "MIME (application/pdf), расширение (pdf, .docx, rar) или общее слово (video, image, audio, office, archive, pdf)")
                    }
                    putJsonObject("task") {
                        put("type", "string")
                        put("description", "Краткое описание задачи, если формат не задан (например «открыть pdf», «edit a word document»)")
                    }
                }
            },
        ),
        Tool(
            name = "app_backup_apk",
            description = "Скопировать APK-файлы установленного приложения (package): базовый и все split-APK — в папку destPath. destPath необязателен: по умолчанию Download/apk_backup в доступном хранилище. Возвращает пути и размеры скопированных файлов.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("package") { put("type", "string"); put("description", "Пакет приложения, напр. com.android.chrome") }
                    putJsonObject("destPath") { put("type", "string"); put("description", "Папка для копий APK (необязательно; по умолчанию Download/apk_backup)") }
                }
                putJsonArray("required") { add(JsonPrimitive("package")) }
            },
        ),
        Tool(
            name = "sms_thread",
            description = "Показать переписку SMS с указанным номером (по возрастанию времени).",
            needsPermission = "android.permission.READ_SMS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("phone") { put("type", "string"); put("description", "Номер телефона") }
                    putJsonObject("limit") { put("type", "string"); put("description", "Максимум сообщений (опционально)") }
                }
                putJsonArray("required") { add(JsonPrimitive("phone")) }
            },
        ),
        Tool(
            name = "call_log_delete",
            description = "Удалить записи из журнала звонков: all=true (всё), либо id, либо phone. Действие опасное.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CALL_LOG",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("all") { put("type", "boolean"); put("description", "true — удалить весь журнал") }
                    putJsonObject("id") { put("type", "string"); put("description", "Идентификатор записи") }
                    putJsonObject("phone") { put("type", "string"); put("description", "Номер телефона; формат не важен (совпадение по значимым цифрам)") }
                }
            },
        ),
    )
}
