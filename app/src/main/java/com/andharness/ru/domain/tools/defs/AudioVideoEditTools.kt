package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- audio / video editing (bug-items 11 and 12) ----
//
// Every tool here writes a NEW file and never touches the source. The actual muxing/encoding is
// done by the bundled ffmpeg (LGPL build, `dev.ffmpegkit-maintained:ffmpeg-kit-full`) through the
// `com.arthenica.ffmpegkit` API; see [com.andharness.ru.data.tools.media.FfmpegRunner].
internal object AudioVideoEditTools {
    fun defs(): List<Tool> = listOf(
        // ================= AUDIO =================
        Tool(
            name = "audio_trim",
            description = "Обрезать аудиофайл по времени в новый файл dest (исходный не меняется). " +
                "start — начало (секунды, «90», «1:30» или «0:01:30»), end — конец, либо duration — " +
                "длительность; нужно указать end или duration. По умолчанию дорожка копируется без " +
                "перекодирования (reencode=false); поставь reencode=true, если контейнер назначения не " +
                "поддерживает исходный кодек. Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному аудиофайлу.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь к новому файлу-результату (не должен существовать).")
                    }
                    putJsonObject("start") {
                        put("type", "string")
                        put("description", "Начало фрагмента: секунды или «1:30»/«0:01:30» (по умолчанию 0).")
                    }
                    putJsonObject("end") {
                        put("type", "string")
                        put("description", "Конец фрагмента в той же форме, что start (нужен end или duration).")
                    }
                    putJsonObject("duration") {
                        put("type", "string")
                        put("description", "Длительность фрагмента в секундах (альтернатива end).")
                    }
                    putJsonObject("reencode") {
                        put("type", "boolean")
                        put("description", "Перекодировать дорожку под формат dest (по умолчанию false — копия).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "audio_concat",
            description = "Склеить несколько аудиофайлов в один новый файл dest. paths — список путей " +
                "через запятую или точку с запятой (в порядке склейки). По умолчанию только " +
                "перепаковка без перекодирования, поэтому файлы должны иметь совместимый кодек и " +
                "параметры; иначе поставь reencode=true. Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("paths") {
                        put("type", "string")
                        put("description", "Пути к аудиофайлам через запятую/точку с запятой, в порядке склейки.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь к новому файлу-результату (не должен существовать).")
                    }
                    putJsonObject("reencode") {
                        put("type", "boolean")
                        put("description", "Перекодировать при склейке (по умолчанию false — только перепаковка).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("paths")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "audio_convert",
            description = "Конвертировать аудио в другой формат. Формат определяется расширением dest " +
                "(mp3, wav, ogg, opus, m4a, aac, flac, wma) либо параметром codec. Необязательные " +
                "bitrate (кбит/с) и sample_rate (Гц). Неподдерживаемый формат — честный отказ, а не " +
                "ложный успех. Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному аудиофайлу.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь к новому файлу-результату (расширение задаёт формат).")
                    }
                    putJsonObject("codec") {
                        put("type", "string")
                        put("description", "Явный кодек/формат (необязательно; по умолчанию из расширения dest).")
                    }
                    putJsonObject("bitrate") {
                        put("type", "string")
                        put("description", "Битрейт в кбит/с, например 192 (необязательно).")
                    }
                    putJsonObject("sample_rate") {
                        put("type", "string")
                        put("description", "Частота дискретизации в Гц, например 44100 (необязательно).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "audio_volume",
            description = "Изменить громкость аудио в новый файл dest. Задай gain_db (децибелы, например " +
                "«6» или «-3») ИЛИ percent (проценты, например «150»). Ровно один из параметров " +
                "обязателен. Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному аудиофайлу.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь к новому файлу-результату (не должен существовать).")
                    }
                    putJsonObject("gain_db") {
                        put("type", "string")
                        put("description", "Изменение громкости в дБ, например «6» или «-3».")
                    }
                    putJsonObject("percent") {
                        put("type", "string")
                        put("description", "Громкость в процентах от исходной, например «150» (альтернатива gain_db).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "audio_speed",
            description = "Изменить темп аудио (без изменения высоты голоса) в новый файл dest. factor — " +
                "множитель скорости (например 1.5 — быстрее, 0.75 — медленнее). Для значений вне " +
                "диапазона 0.5–2.0 фильтры выстраиваются цепочкой. Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному аудиофайлу.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь к новому файлу-результату (не должен существовать).")
                    }
                    putJsonObject("factor") {
                        put("type", "string")
                        put("description", "Множитель скорости, например 1.5 (больше 1 — быстрее).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path")); add(JsonPrimitive("dest")); add(JsonPrimitive("factor"))
                }
            },
        ),
        Tool(
            name = "audio_extract_from_video",
            description = "Извлечь аудиодорожку из видеофайла в отдельный аудиофайл dest. По умолчанию " +
                "без перекодирования (reencode=false); если дорожку нельзя положить в контейнер dest " +
                "как есть — поставь reencode=true, тогда кодек подберётся по расширению dest. Честно " +
                "сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному видеофайлу.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь к новому аудиофайлу (расширение задаёт формат при reencode).")
                    }
                    putJsonObject("reencode") {
                        put("type", "boolean")
                        put("description", "Перекодировать аудио (по умолчанию false — копия дорожки).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "audio_tag_write",
            description = "Записать метаданные аудиофайла (название, исполнитель, альбом и т.п.) в новый " +
                "файл dest, не меняя исходный. Укажи хотя бы одно поле. Поддержка тегов зависит от " +
                "формата (mp3/m4a/flac/ogg — да; wav — ограниченно); контейнеры без тегов дадут " +
                "честное сообщение. Файл копируется без перекодирования.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному аудиофайлу.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь к новому файлу-результату (не должен существовать).")
                    }
                    putJsonObject("title") { put("type", "string"); put("description", "Название.") }
                    putJsonObject("artist") { put("type", "string"); put("description", "Исполнитель.") }
                    putJsonObject("album") { put("type", "string"); put("description", "Альбом.") }
                    putJsonObject("album_artist") {
                        put("type", "string"); put("description", "Исполнитель альбома.")
                    }
                    putJsonObject("genre") { put("type", "string"); put("description", "Жанр.") }
                    putJsonObject("track") { put("type", "string"); put("description", "Номер трека.") }
                    putJsonObject("date") { put("type", "string"); put("description", "Дата/год.") }
                    putJsonObject("comment") { put("type", "string"); put("description", "Комментарий.") }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        // ================= VIDEO =================
        Tool(
            name = "video_trim",
            description = "Обрезать видео по времени в новый файл dest (исходный не меняется). start — " +
                "начало, end — конец, либо duration — длительность (нужно указать end или duration). " +
                "По умолчанию дорожки копируются без перекодирования (reencode=false, быстро, но рез " +
                "по ближайшему ключевому кадру); reencode=true даёт точный рез с перекодированием. " +
                "Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string"); put("description", "Путь к исходному видеофайлу.")
                    }
                    putJsonObject("dest") {
                        put("type", "string"); put("description", "Путь к новому файлу-результату.")
                    }
                    putJsonObject("start") {
                        put("type", "string"); put("description", "Начало: секунды или «1:30»/«0:01:30» (по умолчанию 0).")
                    }
                    putJsonObject("end") {
                        put("type", "string"); put("description", "Конец в той же форме, что start (нужен end или duration).")
                    }
                    putJsonObject("duration") {
                        put("type", "string"); put("description", "Длительность фрагмента в секундах (альтернатива end).")
                    }
                    putJsonObject("reencode") {
                        put("type", "boolean"); put("description", "Перекодировать для точного реза (по умолчанию false — копия).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "video_concat",
            description = "Склеить несколько видеофайлов в один новый файл dest. paths — список путей " +
                "через запятую или точку с запятой (в порядке склейки). По умолчанию только " +
                "перепаковка без перекодирования (файлы должны иметь одинаковые кодек, разрешение и " +
                "параметры); иначе поставь reencode=true. Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("paths") {
                        put("type", "string"); put("description", "Пути к видеофайлам через запятую/точку с запятой, в порядке склейки.")
                    }
                    putJsonObject("dest") {
                        put("type", "string"); put("description", "Путь к новому файлу-результату (не должен существовать).")
                    }
                    putJsonObject("reencode") {
                        put("type", "boolean"); put("description", "Перекодировать при склейке (по умолчанию false — только перепаковка).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("paths")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "video_resize",
            description = "Изменить разрешение видео в новый файл dest. Задай width и/или height (пиксели) " +
                "либо maxSide (ограничение длинной стороны). Если задана только одна сторона, вторая " +
                "подбирается с сохранением пропорций. Требует перекодирования. Честно сообщает путь и " +
                "размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к исходному видеофайлу.") }
                    putJsonObject("dest") { put("type", "string"); put("description", "Путь к новому файлу-результату.") }
                    putJsonObject("width") { put("type", "string"); put("description", "Ширина в пикселях.") }
                    putJsonObject("height") { put("type", "string"); put("description", "Высота в пикселях.") }
                    putJsonObject("maxSide") { put("type", "string"); put("description", "Ограничение длинной стороны в пикселях.") }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "video_bitrate",
            description = "Перекодировать видео с заданным битрейтом в новый файл dest. video_bitrate — " +
                "битрейт видео в кбит/с (обязателен); audio_bitrate — битрейт звука в кбит/с " +
                "(необязательно). Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к исходному видеофайлу.") }
                    putJsonObject("dest") { put("type", "string"); put("description", "Путь к новому файлу-результату.") }
                    putJsonObject("video_bitrate") { put("type", "string"); put("description", "Битрейт видео в кбит/с, например 4000.") }
                    putJsonObject("audio_bitrate") { put("type", "string"); put("description", "Битрейт звука в кбит/с, например 128 (необязательно).") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path")); add(JsonPrimitive("dest")); add(JsonPrimitive("video_bitrate"))
                }
            },
        ),
        Tool(
            name = "video_compress",
            description = "Сжать видео в новый файл dest. crf — качество (меньше = лучше, типично 23–32, " +
                "по умолчанию 28); preset — скорость/размер (ultrafast…veryslow, по умолчанию medium); " +
                "maxSide — при желании дополнительно уменьшить разрешение по длинной стороне. Честно " +
                "сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к исходному видеофайлу.") }
                    putJsonObject("dest") { put("type", "string"); put("description", "Путь к новому файлу-результату.") }
                    putJsonObject("crf") { put("type", "string"); put("description", "Качество CRF 18–32 (по умолчанию 28).") }
                    putJsonObject("preset") { put("type", "string"); put("description", "Пресет x264: ultrafast…veryslow (по умолчанию medium).") }
                    putJsonObject("maxSide") { put("type", "string"); put("description", "Ограничение длинной стороны в пикселях (необязательно).") }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "video_rotate",
            description = "Повернуть видео на 90, 180 или 270 градусов в новый файл dest. Требует " +
                "перекодирования. Честно сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к исходному видеофайлу.") }
                    putJsonObject("dest") { put("type", "string"); put("description", "Путь к новому файлу-результату.") }
                    putJsonObject("rotation") { put("type", "string"); put("description", "Угол поворота: 90, 180 или 270.") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path")); add(JsonPrimitive("dest")); add(JsonPrimitive("rotation"))
                }
            },
        ),
        Tool(
            name = "video_extract_audio",
            description = "Извлечь звуковую дорожку из видео в отдельный аудиофайл dest (синоним " +
                "audio_extract_from_video). По умолчанию копирование без перекодирования " +
                "(reencode=false); иначе кодек подбирается по расширению dest. Честно сообщает путь и " +
                "размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к исходному видеофайлу.") }
                    putJsonObject("dest") { put("type", "string"); put("description", "Путь к новому аудиофайлу.") }
                    putJsonObject("reencode") {
                        put("type", "boolean"); put("description", "Перекодировать аудио (по умолчанию false — копия дорожки).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "video_subtitles",
            description = "Добавить субтитры из файла srt в видео (новый файл dest). mode=burn — вжечь " +
                "субтитры в картинку (их видно всегда); mode=mux — вложить как отдельную дорожку " +
                "(по умолчанию). srt — путь к файлу субтитров .srt. Честно сообщает путь и размер " +
                "результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к исходному видеофайлу.") }
                    putJsonObject("dest") { put("type", "string"); put("description", "Путь к новому файлу-результату.") }
                    putJsonObject("srt") { put("type", "string"); put("description", "Путь к файлу субтитров .srt.") }
                    putJsonObject("mode") { put("type", "string"); put("description", "burn — вжечь; mux — вложить дорожкой (по умолчанию mux).") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path")); add(JsonPrimitive("dest")); add(JsonPrimitive("srt"))
                }
            },
        ),
        Tool(
            name = "video_convert",
            description = "Сменить контейнер/формат видео в новый файл dest (например mp4 → mkv). По " +
                "умолчанию перепаковка без перекодирования (reencode=false), если новые кодек и " +
                "контейнер совместимы; иначе поставь reencode=true для перекодирования. Честно " +
                "сообщает путь и размер результата.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к исходному видеофайлу.") }
                    putJsonObject("dest") { put("type", "string"); put("description", "Путь к новому файлу-результату (расширение задаёт контейнер).") }
                    putJsonObject("reencode") {
                        put("type", "boolean"); put("description", "Перекодировать дорожки (по умолчанию false — перепаковка).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("dest")) }
            },
        ),
    )
}
