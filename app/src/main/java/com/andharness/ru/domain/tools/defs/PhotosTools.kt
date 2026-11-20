package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- photos ----
internal object PhotosTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "photos_list",
            description = "Список фото из галереи (по желанию — поиск по имени). Возвращает id, имя и размер. Передай query для поиска по имени файла.",
            needsPermission = "android.permission.READ_MEDIA_IMAGES",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Текст для поиска по имени файла (опционально)")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Максимальное количество возвращаемых фото")
                    }
                }
            },
        ),
        Tool(
            name = "photos_delete",
            description = "Удалить фото из галереи по списку id. Для удаления нужен доступ ко всем файлам.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("ids") {
                        put("type", "string")
                        put("description", "Список id фото для удаления через запятую, например: 1,2,3")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("ids")) }
            },
        ),
        Tool(
            name = "exif_read",
            description = "Прочитать EXIF-метаданные изображения (дата, GPS, модель камеры, ориентация, размеры).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу изображения (в общем хранилище или /storage/emulated/0).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "exif_write",
            description = "Изменить EXIF-тег изображения и сохранить файл.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу изображения (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("tag") {
                        put("type", "string")
                        put("description", "Имя EXIF-тега: короткое имя, например DateTimeOriginal, или константа, например TAG_DATETIME_ORIGINAL (регистр не важен)")
                    }
                    putJsonObject("value") {
                        put("type", "string")
                        put("description", "Новое значение тега (строка, например 2026:09:16 12:00:00)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("tag"))
                    add(JsonPrimitive("value"))
                }
            },
        ),
        Tool(
            name = "image_thumbnail",
            description = "Создать уменьшенную миниатюру изображения или видео.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению или видео.")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь для сохранения миниатюры (PNG).")
                    }
                    putJsonObject("size") {
                        put("type", "string")
                        put("description", "Длина большей стороны в пикселях (по умолчанию 256).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),
        Tool(
            name = "image_resize",
            description = "Изменить размер изображения и сохранить результат. Размер задаётся width и/или height в пикселях либо maxSide — по большей стороне; при указании только одной стороны пропорции сохраняются. Формат результата — jpeg, png или webp.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения (.jpg, .png или .webp).")
                    }
                    putJsonObject("width") {
                        put("type", "string")
                        put("description", "Ширина результата в пикселях (необязательно).")
                    }
                    putJsonObject("height") {
                        put("type", "string")
                        put("description", "Высота результата в пикселях (необязательно).")
                    }
                    putJsonObject("maxSide") {
                        put("type", "string")
                        put("description", "Длина большей стороны, px.")
                    }
                    putJsonObject("format") {
                        put("type", "string")
                        put("description", "Формат (по умолчанию — по расширению dest, иначе jpeg).")
                        putJsonArray("enum") {
                            add(JsonPrimitive("jpeg"))
                            add(JsonPrimitive("png"))
                            add(JsonPrimitive("webp"))
                        }
                    }
                    putJsonObject("quality") {
                        put("type", "string")
                        put("description", "Качество 1–100 для jpeg/webp (по умолчанию 85).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                }
            },
        ),
        Tool(
            name = "image_convert",
            description = "Конвертировать изображение в другой формат: jpeg, png или webp. Размер не меняется, если не заданы width, height или maxSide.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения (.jpg, .png или .webp).")
                    }
                    putJsonObject("format") {
                        put("type", "string")
                        put("description", "Формат (по умолчанию — по расширению dest, иначе jpeg).")
                        putJsonArray("enum") {
                            add(JsonPrimitive("jpeg"))
                            add(JsonPrimitive("png"))
                            add(JsonPrimitive("webp"))
                        }
                    }
                    putJsonObject("quality") {
                        put("type", "string")
                        put("description", "Качество 1–100 для jpeg/webp (по умолчанию 85).")
                    }
                    putJsonObject("width") {
                        put("type", "string")
                        put("description", "Ширина результата в пикселях (необязательно; пропорции сохраняются, если задана только она).")
                    }
                    putJsonObject("height") {
                        put("type", "string")
                        put("description", "Высота результата в пикселях (необязательно; пропорции сохраняются, если задана только она).")
                    }
                    putJsonObject("maxSide") {
                        put("type", "string")
                        put("description", "Длина большей стороны, px.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                }
            },
        ),
        Tool(
            name = "image_crop",
            description = "Обрезать изображение по прямоугольнику и сохранить в новый файл. Область задаётся x, y, width, height в пикселях либо box=x1,y1,x2,y2 — долями от ширины/высоты (0..1). Исходный файл не изменяется.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения нового файла.")
                    }
                    putJsonObject("x") {
                        put("type", "string")
                        put("description", "Левая граница обрезки, px (по умолчанию 0).")
                    }
                    putJsonObject("y") {
                        put("type", "string")
                        put("description", "Верхняя граница обрезки, px (по умолчанию 0).")
                    }
                    putJsonObject("width") {
                        put("type", "string")
                        put("description", "Ширина области обрезки в пикселях.")
                    }
                    putJsonObject("height") {
                        put("type", "string")
                        put("description", "Высота области обрезки в пикселях.")
                    }
                    putJsonObject("box") {
                        put("type", "string")
                        put("description", "Альтернатива width/height: доли 0..1 «x1,y1,x2,y2» (например 0.1,0.1,0.9,0.9).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                }
            },
        ),
        Tool(
            name = "image_rotate",
            description = "Повернуть изображение на угол (90, 180, 270 или произвольный) и/или отразить по горизонтали/вертикали, сохранив в новый файл. Для произвольного угла фон заливки задаётся background (например #RRGGBB), иначе фон прозрачный.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения нового файла.")
                    }
                    putJsonObject("angle") {
                        put("type", "string")
                        put("description", "Угол поворота в градусах (например 90, 180, 270 или 45). По умолчанию 0.")
                    }
                    putJsonObject("flip") {
                        put("type", "string")
                        put("description", "Отражение: none (по умолчанию), h, v или both.")
                        putJsonArray("enum") {
                            add(JsonPrimitive("none"))
                            add(JsonPrimitive("h"))
                            add(JsonPrimitive("v"))
                            add(JsonPrimitive("both"))
                        }
                    }
                    putJsonObject("background") {
                        put("type", "string")
                        put("description", "Цвет фона (#RRGGBB) для незакрытых углов при произвольном угле.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                }
            },
        ),
        Tool(
            name = "image_adjust",
            description = "Настроить яркость, контраст, насыщенность (−1..1) и резкость (0..1) изображения и сохранить в новый файл. Значение 0 — без изменений; хотя бы один параметр должен быть не нулевым.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения нового файла.")
                    }
                    putJsonObject("brightness") {
                        put("type", "string")
                        put("description", "Яркость от −1 до 1 (0 — без изменений).")
                    }
                    putJsonObject("contrast") {
                        put("type", "string")
                        put("description", "Контраст от −1 до 1 (0 — без изменений).")
                    }
                    putJsonObject("saturation") {
                        put("type", "string")
                        put("description", "Насыщенность от −1 (ч/б) до 1 (0 — без изменений).")
                    }
                    putJsonObject("sharpness") {
                        put("type", "string")
                        put("description", "Резкость от 0 до 1 (0 — выключена).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                }
            },
        ),
        Tool(
            name = "image_watermark",
            description = "Нанести водяной знак — текст (text) или небольшую картинку (image) — и сохранить в новый файл. Позиция: top-left, top-right, bottom-left, bottom-right, center. Можно задать размер, цвет, прозрачность, отступ и повтор (tiled).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения нового файла.")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст водяного знака (используется первым, если задан).")
                    }
                    putJsonObject("image") {
                        put("type", "string")
                        put("description", "Путь к картинке-знаку (если text не задан).")
                    }
                    putJsonObject("position") {
                        put("type", "string")
                        put("description", "Положение: top-left, top-right, bottom-left, bottom-right (по умолчанию), center.")
                    }
                    putJsonObject("size") {
                        put("type", "string")
                        put("description", "Размер: кегль текста или ширина картинки-знака, px (по умолчанию ~1/16 стороны).")
                    }
                    putJsonObject("color") {
                        put("type", "string")
                        put("description", "Цвет текста (#RRGGBB или #AARRGGBB; по умолчанию белый).")
                    }
                    putJsonObject("opacity") {
                        put("type", "string")
                        put("description", "Прозрачность от 0 до 1 (по умолчанию 1).")
                    }
                    putJsonObject("margin") {
                        put("type", "string")
                        put("description", "Отступ от края в пикселях (по умолчанию 16).")
                    }
                    putJsonObject("tiled") {
                        put("type", "boolean")
                        put("description", "true — повторить знак сеткой по всему изображению.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                }
            },
        ),
        Tool(
            name = "image_overlay_text",
            description = "Написать произвольный текст на изображении в заданной позиции и сохранить в новый файл. Можно задать размер шрифта, цвет, выравнивание (left/center/right), прозрачность и подложку.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения нового файла.")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст для нанесения.")
                    }
                    putJsonObject("x") {
                        put("type", "string")
                        put("description", "Горизонтальный якорь, px (альтернатива position).")
                    }
                    putJsonObject("y") {
                        put("type", "string")
                        put("description", "Верх текста, px (альтернатива position).")
                    }
                    putJsonObject("position") {
                        put("type", "string")
                        put("description", "Положение, если не заданы x/y: top-left, top-right, bottom-left, bottom-right, center.")
                    }
                    putJsonObject("size") {
                        put("type", "string")
                        put("description", "Размер шрифта в пикселях (по умолчанию ~1/20 стороны).")
                    }
                    putJsonObject("color") {
                        put("type", "string")
                        put("description", "Цвет текста (#RRGGBB или #AARRGGBB; по умолчанию белый).")
                    }
                    putJsonObject("align") {
                        put("type", "string")
                        put("description", "Выравнивание относительно x: left (по умолчанию), center, right.")
                        putJsonArray("enum") {
                            add(JsonPrimitive("left"))
                            add(JsonPrimitive("center"))
                            add(JsonPrimitive("right"))
                        }
                    }
                    putJsonObject("opacity") {
                        put("type", "string")
                        put("description", "Прозрачность от 0 до 1 (по умолчанию 1).")
                    }
                    putJsonObject("background") {
                        put("type", "string")
                        put("description", "Цвет подложки под текст (#RRGGBB или #AARRGGBB; без подложки, если не задан).")
                    }
                    putJsonObject("padding") {
                        put("type", "string")
                        put("description", "Отступ подложки/размещения в пикселях (по умолчанию 8).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                    add(JsonPrimitive("text"))
                }
            },
        ),
        Tool(
            name = "image_collage",
            description = "Собрать коллаж из нескольких изображений в сетку и сохранить в новый файл. srcs — пути через запятую. Сетка задаётся rows/cols (иначе подбирается автоматически); spacing — отступ между ячейками, background — цвет фона.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("srcs") {
                        put("type", "string")
                        put("description", "Пути к изображениям через запятую или точку с запятой.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения нового файла.")
                    }
                    putJsonObject("rows") {
                        put("type", "string")
                        put("description", "Число строк сетки (по умолчанию подбирается).")
                    }
                    putJsonObject("cols") {
                        put("type", "string")
                        put("description", "Число столбцов сетки (по умолчанию подбирается).")
                    }
                    putJsonObject("spacing") {
                        put("type", "string")
                        put("description", "Отступ между ячейками и по краям, px (по умолчанию 8).")
                    }
                    putJsonObject("background") {
                        put("type", "string")
                        put("description", "Цвет фона коллажа (#RRGGBB; по умолчанию чёрный).")
                    }
                    putJsonObject("cellWidth") {
                        put("type", "string")
                        put("description", "Ширина ячейки, px (по умолчанию — по наибольшему изображению).")
                    }
                    putJsonObject("cellHeight") {
                        put("type", "string")
                        put("description", "Высота ячейки, px (по умолчанию — по наибольшему изображению).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("srcs"))
                    add(JsonPrimitive("dest"))
                }
            },
        ),
        Tool(
            name = "screen_annotate",
            description = "Нанести аннотации на изображение (например, скриншот) и сохранить в новый файл: прямоугольники, эллипсы, стрелки, текст и подсветка из массива shapes в JSON. Формат элемента: {\"type\":\"rect|ellipse|arrow|text|highlight\", ...}.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению (например, скриншоту).")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения нового файла.")
                    }
                    putJsonObject("shapes") {
                        put("type", "string")
                        put("description", "JSON-массив фигур. rect/ellipse: x,y,w,h; arrow: x1,y1,x2,y2; text: x,y,text,size; highlight: x,y,w,h. Общий цвет color.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                    add(JsonPrimitive("shapes"))
                }
            },
        ),
        Tool(
            name = "image_svg_render",
            description = "Отрисовать SVG (векторную картинку) в растровый PNG заданной ширины/высоты. Источник — файл (src) или встроенный ресурс приложения (asset): ровно одно из двух. Если задать только одну сторону, пропорции сохраняются; без размеров берётся собственный размер SVG. AndroidSVG рисует статический SVG; часть возможностей (фильтры, отдельные CSS) не поддерживается и просто игнорируется.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к файлу .svg (альтернатива asset).")
                    }
                    putJsonObject("asset") {
                        put("type", "string")
                        put("description", "Имя встроенного ресурса приложения .svg (альтернатива src).")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения PNG (прозрачный фон).")
                    }
                    putJsonObject("width") {
                        put("type", "string")
                        put("description", "Ширина результата в пикселях (необязательно).")
                    }
                    putJsonObject("height") {
                        put("type", "string")
                        put("description", "Высота результата в пикселях (необязательно).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("dest")) }
            },
        ),
        Tool(
            name = "image_remove_background",
            description = "Убрать фон у изображения: вернуть PNG с вырезанным субъектом (передний план) и прозрачным фоном. Использует ML Kit Subject Segmentation (бета). Модель НЕ встроена в приложение — она загружается через Google Play services, поэтому нужно подключение и первый запуск может подождать загрузки; при недоступности модель/сервис инструмент честно сообщает об этом и не подделывает результат.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("src") {
                        put("type", "string")
                        put("description", "Путь к исходному изображению.")
                    }
                    putJsonObject("dest") {
                        put("type", "string")
                        put("description", "Путь сохранения PNG с прозрачным фоном.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("src"))
                    add(JsonPrimitive("dest"))
                }
            },
        ),
        Tool(
            name = "image_detect_objects",
            description = "Найти объекты на изображении (ML Kit Object Detection, модель встроена в приложение, работает офлайн) и вернуть JSON-список объектов: {label, confidence, box{x,y,w,h}}, отсортированный по убыванию уверенности и ограниченный maxResults. Категории грубые: Fashion good, Home good, Food, Place, Plant. Если объектов нет — возвращает пустой список [].",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к изображению для анализа.")
                    }
                    putJsonObject("maxResults") {
                        put("type", "string")
                        put("description", "Максимум объектов в ответе (по умолчанию 20, не больше 100).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "wallpaper_set",
            description = "Установить картинку на обои («поставь картинку на обои»). Источник — локальный файл (path) или ссылка (url) — ровно одно из двух. target: home (рабочий стол, по умолчанию), lock (экран блокировки) или both (оба).",
            needsPermission = "android.permission.SET_WALLPAPER",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к локальному файлу-картинке (например, из camera_* или download_file).")
                    }
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Ссылка на картинку в интернете — агент скачает её сам.")
                    }
                    putJsonObject("target") {
                        put("type", "string")
                        put("description", "Куда поставить: home (по умолчанию), lock или both.")
                    }
                }
            },
        ),
        Tool(
            name = "media_scan",
            description = "Просканировать файл в медиатеку, чтобы он появился в Галерее и других медиа-приложениях.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу (в общем хранилище).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "media_delete",
            description = "Удалить медиафайлы (фото/видео/аудио) с системным диалогом подтверждения.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("paths") {
                        put("type", "string")
                        put("description", "Пути к файлам через запятую (в общем хранилище).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("paths")) }
            },
        ),
        Tool(
            name = "media_trash",
            description = "Поместить медиафайлы в системную Корзину (с подтверждением).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("paths") {
                        put("type", "string")
                        put("description", "Пути к файлам через запятую (в общем хранилище).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("paths")) }
            },
        ),
        Tool(
            name = "media_request_write",
            description = "Запросить у пользователя право на изменение чужих медиафайлов.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("paths") {
                        put("type", "string")
                        put("description", "Пути к файлам через запятую (в общем хранилище).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("paths")) }
            },
        ),
        Tool(
            name = "media_downloads_list",
            description = "Список файлов из папки Загрузки (MediaStore.Downloads). Необязательный limit — максимум записей (по умолчанию 100).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Максимальное количество возвращаемых файлов (по умолчанию 100)")
                    }
                }
            },
        ),
        Tool(
            name = "media_metadata",
            description = "Метаданные медиафайла: длительность, разрешение, заголовок, исполнитель.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к аудио/видеофайлу (в общем хранилище).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "media_frame_extract",
            description = "Извлечь кадр из видео в PNG. time_ms — момент в миллисекундах (по умолчанию 0).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к видеофайлу (в общем хранилище).")
                    }
                    putJsonObject("time_ms") {
                        put("type", "string")
                        put("description", "Момент кадра в миллисекундах (по умолчанию 0)")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь для сохранения кадра в формате PNG")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),

        Tool(
            name = "media_favorite_set",
            description = "Добавить или убрать медиафайлы (фото/видео/аудио) из системного избранного. Работает с подтверждением системы, требует Android 11+.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("paths") {
                        put("type", "string")
                        put("description", "Пути к файлам через запятую (в общем хранилище).")
                    }
                    putJsonObject("favorite") {
                        put("type", "boolean")
                        put("description", "true — добавить в избранное (по умолчанию), false — убрать.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("paths")) }
            },
        ),
    )
}
