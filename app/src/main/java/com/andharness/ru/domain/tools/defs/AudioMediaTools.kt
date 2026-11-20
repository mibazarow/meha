package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- audio / media sessions ----
internal object AudioMediaTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "audio_play",
            description = "Воспроизвести аудиофайл с запросом аудиофокуса. wait=true — дождаться окончания воспроизведения (ограниченное время), иначе вернуть, что воспроизведение началось. position_ms — начальная позиция в миллисекундах.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к аудиофайлу (в общем хранилище).")
                    }
                    putJsonObject("wait") {
                        put("type", "boolean")
                        put("description", "Дождаться окончания воспроизведения (по умолчанию false)")
                    }
                    putJsonObject("position_ms") {
                        put("type", "string")
                        put("description", "Начальная позиция воспроизведения в миллисекундах (по умолчанию 0)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "audio_stop",
            description = "Остановить текущее воспроизведение, запущенное инструментом audio_play. Честно сообщает, если ничего не воспроизводится.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "audio_mode_set",
            description = "Установить режим аудио: normal, in_communication, in_call, ringtone. Необязательный speaker=true/false переключает громкую связь. Честно сообщает, если платформа проигнорировала режим.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("mode") {
                        put("type", "string")
                        put("description", "Режим аудио: normal, in_communication, in_call или ringtone")
                    }
                    putJsonObject("speaker") {
                        put("type", "boolean")
                        put("description", "Включить (true) или выключить (false) громкую связь (необязательно)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("mode")) }
            },
        ),
        Tool(
            name = "audio_stream_analysis",
            description = "Записать короткое окно с микрофона и посчитать уровень звука: RMS, пик и долю тишины. Файлы не создаёт. duration_ms по умолчанию 3000, максимум 10000.",
            needsPermission = "android.permission.RECORD_AUDIO",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("duration_ms") {
                        put("type", "string")
                        put("description", "Длительность окна анализа в миллисекундах (по умолчанию 3000, максимум 10000)")
                    }
                }
            },
        ),
        Tool(
            name = "audio_capture_app",
            description = "Записать звук, воспроизводимый другими приложениями, в WAV-файл. Требует разрешение RECORD_AUDIO и подтверждение захвата системного звука. Приложение-источник может запретить захват — тогда будет записана тишина, о чём инструмент честно сообщит.",
            dangerous = true,
            needsPermission = "android.permission.RECORD_AUDIO",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу записи (.wav; в общем хранилище).")
                    }
                    putJsonObject("duration_ms") {
                        put("type", "string")
                        put("description", "Длительность записи в миллисекундах (по умолчанию 5000, максимум 60000)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "media_control_other",
            description = "Управлять другим медиаплеером через его активную медиа-сессию. action: play, pause, next, previous, stop или toggle. Необязательный app — фильтр по имени пакета. Требует доступ к уведомлениям.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "Команда: play, pause, next, previous, stop или toggle")
                    }
                    putJsonObject("app") {
                        put("type", "string")
                        put("description", "Фильтр по имени пакета приложения (необязательно), например com.spotify.music")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("action")) }
            },
        ),
        Tool(
            name = "media_sessions_list",
            description = "Список активных медиа-сессий: кто играет, состояние воспроизведения и метаданные (название, исполнитель, альбом). Необязательный app — фильтр по имени пакета. Требует доступ к уведомлениям.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("app") {
                        put("type", "string")
                        put("description", "Фильтр по имени пакета приложения (необязательно), например com.spotify.music")
                    }
                }
            },
        ),
    )
}
