package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- camera ----
internal object CameraTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "camera_capture",
            description = "Снять фото или видео камерой устройства в ФОНЕ (in-process Camera2, без открытия приложения камеры) и сохранить в файл. type = photo|video. path — файл назначения (необязательно; по умолчанию DCIM/Meha/…). duration — длительность видео в секундах (по умолчанию 10). with_audio — писать ли звук у видео (по умолчанию да). lens = front|back (по умолчанию back). camera_id — точный id из camera_list (имеет приоритет над lens). analyze (только для фото, по умолчанию true): сразу посмотреть снимок — если настроенная модель поддерживает изображения (Vision), фото отправляется модели и возвращается его описание; иначе фото модели НЕ отправляется и честно возвращается распознанный офлайн-OCR текст с пометкой, что Vision недоступен. analyze=false — только сохранить файл. Успех возвращается только после создания непустого файла; файл добавляется в галерею устройства (MediaStore).",
            dangerous = true,
            needsPermission = "android.permission.CAMERA",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("type") {
                        put("type", "string")
                        put("description", "Тип съёмки: photo или video (по умолчанию photo)")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу результата, например Download/photo.jpg (необязательно)")
                    }
                    putJsonObject("duration") {
                        put("type", "string")
                        put("description", "Длительность видео в секундах (только для type=video)")
                    }
                    putJsonObject("with_audio") {
                        put("type", "boolean")
                        put("description", "Писать звук при видеосъёмке (по умолчанию true)")
                    }
                    putJsonObject("lens") {
                        put("type", "string")
                        put("description", "Камера: front (фронтальная) или back (основная, по умолчанию)")
                    }
                    putJsonObject("camera_id") {
                        put("type", "string")
                        put("description", "id камеры из camera_list (важнее lens)")
                    }
                    putJsonObject("analyze") {
                        put("type", "boolean")
                        put("description", "Для фото: посмотреть снимок и вернуть описание (Vision) или офлайн-OCR, если Vision недоступен (по умолчанию true)")
                    }
                }
            },
        ),
        Tool(
            name = "camera_list",
            description = "Список камер устройства и их характеристики: id (для параметра camera_id), положение (front/back), ориентация сенсора, размеры кадров, вспышка, фокусные расстояния.",
            needsPermission = "android.permission.CAMERA",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "camera_zoom",
            description = "Программно задать зум камеры (Camera2): level — коэффициент строкой, например \"2.0\"; либо step = in|out для шага 1.5×. lens = front|back (по умолчанию back), camera_id — точный id из camera_list. Значение ограничивается поддерживаемым диапазоном, применённый зум возвращается.",
            needsPermission = "android.permission.CAMERA",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("level") {
                        put("type", "string")
                        put("description", "Коэффициент зума строкой, например 2.0")
                    }
                    putJsonObject("step") {
                        put("type", "string")
                        put("description", "Шаг зума: in (приблизить) или out (отдалить)")
                    }
                    putJsonObject("lens") {
                        put("type", "string")
                        put("description", "Камера: front|back (по умолчанию back)")
                    }
                    putJsonObject("camera_id") {
                        put("type", "string")
                        put("description", "id камеры из camera_list (важнее lens)")
                    }
                }
            },
        ),
        Tool(
            name = "camera_manual",
            description = "Ручные параметры съёмки (Camera2): iso, exposure_ms (выдержка в миллисекундах), focus_distance (диоптрии, 0 = бесконечность). lens = front|back (по умолчанию back), camera_id — точный id из camera_list. Поддержка проверяется по возможностям камеры; неподдерживаемое честно отклоняется.",
            needsPermission = "android.permission.CAMERA",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("iso") {
                        put("type", "string")
                        put("description", "Светочувствительность ISO целым числом, например 100")
                    }
                    putJsonObject("exposure_ms") {
                        put("type", "string")
                        put("description", "Выдержка в миллисекундах, например 100")
                    }
                    putJsonObject("focus_distance") {
                        put("type", "string")
                        put("description", "Дистанция фокуса в диоптриях (0 = бесконечность)")
                    }
                    putJsonObject("lens") {
                        put("type", "string")
                        put("description", "Камера: front|back (по умолчанию back)")
                    }
                    putJsonObject("camera_id") {
                        put("type", "string")
                        put("description", "id камеры из camera_list (важнее lens)")
                    }
                }
            },
        ),
        Tool(
            name = "camera_burst",
            description = "Серийная съёмка: сделать count кадров (до 20) с интервалом interval_ms в папку destination_dir. lens = front|back (по умолчанию back), camera_id — точный id из camera_list. Каждый файл проверяется на непустоту; кадры добавляются в галерею устройства (MediaStore).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("count") {
                        put("type", "string")
                        put("description", "Число кадров, от 1 до 20")
                    }
                    putJsonObject("interval_ms") {
                        put("type", "string")
                        put("description", "Пауза между кадрами в миллисекундах (необязательно)")
                    }
                    putJsonObject("destination_dir") {
                        put("type", "string")
                        put("description", "Папка назначения (в общем хранилище).")
                    }
                    putJsonObject("lens") {
                        put("type", "string")
                        put("description", "Камера: front|back (по умолчанию back)")
                    }
                    putJsonObject("camera_id") {
                        put("type", "string")
                        put("description", "id камеры из camera_list (важнее lens)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("count"))
                    add(JsonPrimitive("destination_dir"))
                }
            },
        ),
        Tool(
            name = "camera_video_record",
            description = "Записать видео камерой устройства в ФОНЕ (Camera2 + MediaRecorder, без открытия приложения камеры) в файл path. with_audio — писать ли звук (по умолчанию да). duration_ms — длительность записи; если не указана, запись идёт до вызова camera_record_stop. lens = front|back (по умолчанию back), camera_id — точный id из camera_list. Итоговый файл проверяется на непустоту и добавляется в галерею устройства (MediaStore).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу видео, например DCIM/Meha/video.mp4")
                    }
                    putJsonObject("with_audio") {
                        put("type", "boolean")
                        put("description", "Писать звук с микрофона (по умолчанию true)")
                    }
                    putJsonObject("duration_ms") {
                        put("type", "string")
                        put("description", "Длительность записи в миллисекундах (необязательно)")
                    }
                    putJsonObject("lens") {
                        put("type", "string")
                        put("description", "Камера: front|back (по умолчанию back)")
                    }
                    putJsonObject("camera_id") {
                        put("type", "string")
                        put("description", "id камеры из camera_list (важнее lens)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                }
            },
        ),
        Tool(
            name = "camera_record_stop",
            description = "Остановить начатую запись видео (camera_video_record без duration_ms) и проверить, что файл сохранён и непустой.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "audio_record",
            description = "Записать аудио с микрофона на указанное время (в секундах) в файл назначения. " +
                "Если destination не указан, запись сохраняется в Music/Meha/ под автоматическим именем " +
                "(audio_<время>.m4a); если указано имя без расширения, добавляется .m4a. " +
                "Готовый файл регистрируется в галерее устройства.",
            dangerous = true,
            needsPermission = "android.permission.RECORD_AUDIO",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("duration") {
                        put("type", "string")
                        put("description", "Длительность записи в секундах")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put(
                            "description",
                            "Необязательный путь к файлу назначения относительно корня; " +
                                "по умолчанию Music/Meha/audio_<время>.m4a",
                        )
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("duration"))
                }
            },
        ),
    )
}
