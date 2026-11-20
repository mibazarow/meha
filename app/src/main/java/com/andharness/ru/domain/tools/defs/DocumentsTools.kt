package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- documents ----
internal object DocumentsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "doc_info",
            description = "Определить тип и структуру документа (PDF, DOCX, XLSX, PPTX, ODT/ODS/ODP, RTF, CSV, текст) и показать метаданные.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к документу (в общем хранилище или /storage/emulated/0).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "doc_read",
            description = "Извлечь текст из документа (PDF, DOCX, XLSX, PPTX, ODT/ODS/ODP, RTF, CSV/TSV, TXT/MD/JSON/XML/HTML).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к документу (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("page") {
                        put("type", "string")
                        put("description", "Страница для PDF/PPTX (с 1; по умолчанию все).")
                    }
                    putJsonObject("sheet") {
                        put("type", "string")
                        put("description", "Лист для XLSX/ODS: имя или индекс с 1 (по умолчанию первый).")
                    }
                    putJsonObject("max_chars") {
                        put("type", "string")
                        put("description", "Максимум символов в выводе (по умолчанию 100000).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "doc_create",
            description = "Создать документ указанного формата по расширению пути (PDF, DOCX, XLSX, ODT/ODS, RTF, CSV/TSV, TXT/MD/JSON/XML/HTML). PDF пишется со встроенным Unicode-шрифтом, поэтому кириллица сохраняется корректно.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемому документу (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Текст документа; для таблиц (XLSX/ODS/CSV) — строки, ячейки через табуляцию, `;` или `,`.")
                    }
                    putJsonObject("format") {
                        put("type", "string")
                        put("description", "Явный формат, если расширение неоднозначно.")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Название документа (метаданные; необязательно).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("content"))
                }
            },
        ),
        Tool(
            name = "doc_edit",
            description = "Изменить существующий документ: дописать в конец (`append`) или заменить текст (`replace`). Дописывание в PDF использует встроенный Unicode-шрифт (кириллица поддерживается).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к существующему документу (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("mode") {
                        put("type", "string")
                        put("description", "`append` — добавить в конец; `replace` — заменить все вхождения `find`.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Текст для добавления (append) или замены (replace); для таблиц — строки, ячейки через табуляцию, `;` или `,`.")
                    }
                    putJsonObject("find") {
                        put("type", "string")
                        put("description", "Искомый текст (обязателен для `replace`).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("mode"))
                    add(JsonPrimitive("content"))
                }
            },
        ),
        Tool(
            name = "pdf_merge",
            description = "Объединить несколько PDF в один файл (в указанном порядке).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sources") {
                        put("type", "string")
                        put("description", "Пути к PDF через запятую.")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("sources"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),
        Tool(
            name = "pdf_split",
            description = "Извлечь диапазон страниц из PDF в новый файл.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Путь к исходному PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("pages") {
                        put("type", "string")
                        put("description", "Диапазон, напр. 1-3 или 5.")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("source"))
                    add(JsonPrimitive("pages"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),
        Tool(
            name = "pdf_create",
            description = "Создать PDF из текста: A4, поля, автоматический перенос длинных строк, при " +
                "необходимости несколько страниц. Кириллица сохраняется (встроенный Unicode-шрифт). " +
                "Необязательный `title` записывается в метаданные. Итоговый файл проверяется " +
                "(сигнатура %PDF-, размер, число страниц).",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст, из которого создаётся PDF.")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Заголовок (метаданные).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF внутри разрешённой папки, например Documents/report.pdf; расширение .pdf добавляется, если его нет.")
                    }
                    putJsonObject("file_name") {
                        put("type", "string")
                        put("description", "Имя файла без пути (по умолчанию — из title в Documents/Meha).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "pdf_from_markdown",
            description = "Создать PDF из простого Markdown: заголовки `#`/`##`, списки `-`/`*`/`+`, " +
                "полужирный `**текст**`, абзацы. Неподдерживаемый синтаксис (таблицы, блоки кода, " +
                "цитаты `>`, ссылки, нумерованные списки) вставляется как обычный текст. A4, перенос " +
                "строк, несколько страниц; кириллица сохраняется.",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Markdown-текст (поддерживается простое подмножество).")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Заголовок (метаданные).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF внутри разрешённой папки, например Documents/note.pdf; расширение .pdf добавляется, если его нет.")
                    }
                    putJsonObject("file_name") {
                        put("type", "string")
                        put("description", "Имя файла без пути (по умолчанию — из title в Documents/Meha).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "pdf_ocr",
            description = "Распознать текст на сканированной странице PDF (рендер страницы + локальный " +
                "офлайн-OCR: Tesseract, кириллица и латиница; при сбое движка — латинский ML Kit).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("page") {
                        put("type", "string")
                        put("description", "Номер страницы (с 1; по умолчанию 1).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "pdf_rotate",
            description = "Повернуть страницы PDF на 90/180/270° и записать в новый файл. Без " +
                "параметра `pages` поворачиваются все страницы; поворот добавляется к текущему.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Путь к исходному PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("pages") {
                        put("type", "string")
                        put("description", "Диапазон страниц (с 1), напр. 1-3 или 5; по умолчанию все.")
                    }
                    putJsonObject("angle") {
                        put("type", "string")
                        put("description", "Угол поворота: 90, 180 или 270 (по часовой стрелке).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("source"))
                    add(JsonPrimitive("destination"))
                    add(JsonPrimitive("angle"))
                }
            },
        ),
        Tool(
            name = "pdf_watermark",
            description = "Наложить текстовый водяной знак на страницы PDF и записать в новый файл. " +
                "Знак центрируется и рисуется поверх содержимого; кириллица поддерживается " +
                "(встроенный Unicode-шрифт).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Путь к исходному PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст водяного знака.")
                    }
                    putJsonObject("pages") {
                        put("type", "string")
                        put("description", "Диапазон страниц (с 1), напр. 1-3 или 5; по умолчанию все.")
                    }
                    putJsonObject("opacity") {
                        put("type", "string")
                        put("description", "Прозрачность от 0 до 1 (по умолчанию 0.3).")
                    }
                    putJsonObject("font_size") {
                        put("type", "string")
                        put("description", "Размер шрифта в пунктах (по умолчанию 48).")
                    }
                    putJsonObject("angle") {
                        put("type", "string")
                        put("description", "Угол наклона в градусах (по умолчанию 45).")
                    }
                    putJsonObject("color") {
                        put("type", "string")
                        put("description", "Цвет: #rrggbb, #rgb или r,g,b (по умолчанию серый).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("source"))
                    add(JsonPrimitive("destination"))
                    add(JsonPrimitive("text"))
                }
            },
        ),
        Tool(
            name = "pdf_extract_images",
            description = "Извлечь встроенные изображения из страниц PDF в файлы PNG обычного " +
                "размера (каталог `output_dir`). Имена вида page-001-image-01.png; существующие " +
                "файлы пропускаются без overwrite.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("output_dir") {
                        put("type", "string")
                        put("description", "Каталог для PNG (будет создан при необходимости).")
                    }
                    putJsonObject("pages") {
                        put("type", "string")
                        put("description", "Диапазон страниц (с 1), напр. 1-3 или 5; по умолчанию все.")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать существующие PNG.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("output_dir"))
                }
            },
        ),
        Tool(
            name = "pdf_render_pages",
            description = "Отрендерить страницы PDF в PNG с заданным DPI (по умолчанию 150) в " +
                "каталог `output_dir`. Имена вида page-001.png; существующие файлы пропускаются " +
                "без overwrite.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("output_dir") {
                        put("type", "string")
                        put("description", "Каталог для PNG (будет создан при необходимости).")
                    }
                    putJsonObject("pages") {
                        put("type", "string")
                        put("description", "Диапазон страниц (с 1), напр. 1-3 или 5; по умолчанию все.")
                    }
                    putJsonObject("dpi") {
                        put("type", "string")
                        put("description", "Разрешение рендера в DPI (по умолчанию 150; 18–600).")
                    }
                    putJsonObject("prefix") {
                        put("type", "string")
                        put("description", "Префикс имён файлов (по умолчанию page).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать существующие PNG.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("output_dir"))
                }
            },
        ),
        Tool(
            name = "pdf_compress",
            description = "По возможности уменьшить размер PDF в новый файл (пересохранение и, " +
                "если задан `max_image_dimension`, уменьшение крупных изображений). Честно " +
                "сообщает достигнутый размер.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Путь к исходному PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("max_image_dimension") {
                        put("type", "string")
                        put("description", "Максимальная сторона изображения в пикселях; если задано, крупные картинки уменьшаются и перекодируются в JPEG.")
                    }
                    putJsonObject("image_quality") {
                        put("type", "string")
                        put("description", "Качество JPEG 1–100 при уменьшении изображений (по умолчанию 75).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("source"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),
        Tool(
            name = "pdf_fill_form",
            description = "Заполнить поля AcroForm в PDF из JSON-объекта `fields` (имя→значение) и " +
                "записать в новый файл. Сообщает, какие поля заполнены, не найдены или не приняли " +
                "значение; `flatten` вписывает значения в страницы.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Путь к исходному PDF с формой (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("fields") {
                        put("type", "string")
                        put("description", "JSON-объект имя→значение, напр. {\"name\": \"Иван\", \"agree\": \"Yes\"}.")
                    }
                    putJsonObject("flatten") {
                        put("type", "boolean")
                        put("description", "Вписать значения в содержимое страниц (сделать форму нередактируемой).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("source"))
                    add(JsonPrimitive("destination"))
                    add(JsonPrimitive("fields"))
                }
            },
        ),
        Tool(
            name = "pdf_pages",
            description = "Показать структуру PDF: число страниц и для каждой страницы размер " +
                "(пункты/мм) и поворот. Только чтение, файл не изменяется.",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к PDF (в общем хранилище или /storage/emulated/0).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "xlsx_set_cell",
            description = "Записать значение в ячейку XLSX (напр. B2).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к XLSX-файлу (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("cell") {
                        put("type", "string")
                        put("description", "Адрес ячейки, напр. B2.")
                    }
                    putJsonObject("sheet") {
                        put("type", "string")
                        put("description", "Имя или индекс листа (по умолчанию первый).")
                    }
                    putJsonObject("value") {
                        put("type", "string")
                        put("description", "Значение для записи (число или текст).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("cell"))
                    add(JsonPrimitive("value"))
                }
            },
        ),
        Tool(
            name = "pdf_from_template",
            description = "Создать PDF из шаблона: текст/Markdown с плейсхолдерами `{{ключ}}` " +
                "(`{{ ключ }}` тоже подходит) и JSON-объектом значений `values`. Числа, булевы, " +
                "массивы и объекты приводятся к тексту. Плейсхолдеры без значения остаются как " +
                "есть и перечисляются в ответе (честно). A4, перенос строк, кириллица сохраняется.",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("template") {
                        put("type", "string")
                        put("description", "Шаблон (текст/Markdown) с плейсхолдерами вида {{ключ}}.")
                    }
                    putJsonObject("values") {
                        put("type", "string")
                        put("description", "JSON-объект значений вида {\"ключ\": \"значение\"}.")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Заголовок (метаданные).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF, например Documents/report.pdf; расширение .pdf добавляется, если его нет.")
                    }
                    putJsonObject("file_name") {
                        put("type", "string")
                        put("description", "Имя файла без пути (по умолчанию — из title в Documents/Meha).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("template"))
                    add(JsonPrimitive("values"))
                }
            },
        ),
        Tool(
            name = "pdf_from_template_file",
            description = "Создать PDF из шаблона, взятого из существующего локального файла " +
                "(параметр `template_path`): текст/Markdown с плейсхолдерами `{{ключ}}` и " +
                "JSON-объектом значений `values`. Удобно для повторного использования шаблона. " +
                "Плейсхолдеры без значения остаются как есть и перечисляются в ответе.",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("template_path") {
                        put("type", "string")
                        put("description", "Путь к файлу шаблона, текст/Markdown (в общем хранилище).")
                    }
                    putJsonObject("values") {
                        put("type", "string")
                        put("description", "JSON-объект значений вида {\"ключ\": \"значение\"}.")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Заголовок (метаданные).")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PDF; расширение .pdf добавляется, если его нет.")
                    }
                    putJsonObject("file_name") {
                        put("type", "string")
                        put("description", "Имя файла без пути (по умолчанию — из title в Documents/Meha).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("template_path"))
                    add(JsonPrimitive("values"))
                }
            },
        ),
        Tool(
            name = "xlsx_set_cells",
            description = "Записать сразу несколько ячеек в лист XLSX одним вызовом. `cells` — JSON " +
                "либо объект {\"A1\": \"значение\", \"B2\": 42}, либо массив " +
                "[{\"cell\": \"A1\", \"value\": \"…\"}]. Адреса проверяются до записи; при " +
                "overwrite=false уже заполненные ячейки не перезаписываются (честный отказ).",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу XLSX, например Documents/table.xlsx.")
                    }
                    putJsonObject("cells") {
                        put("type", "string")
                        put("description", "JSON ячеек: {\"A1\": \"значение\", \"B2\": 42} или [{\"cell\": \"A1\", \"value\": \"…\"}].")
                    }
                    putJsonObject("sheet") {
                        put("type", "string")
                        put("description", "Имя или индекс листа (по умолчанию первый).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписывать ячейки с уже имеющимся значением (по умолчанию да, как у xlsx_set_cell).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("cells"))
                }
            },
        ),
        Tool(
            name = "docx_replace",
            description = "Заменить текст в DOCX. `replacements` — JSON old→new (литерально), " +
                "`template_values` — JSON ключ→значение с подстановкой `{{ключ}}`; можно совмещать. " +
                "Ограничение: плейсхолдер, разбитый между несколькими runs, не находится. По " +
                "умолчанию правка на месте (с резервной копией), либо копия в output_path/file_name.",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к существующему DOCX, например Documents/contract.docx.")
                    }
                    putJsonObject("replacements") {
                        put("type", "string")
                        put("description", "JSON-объект замен old→new: {\"старое\": \"новое\"}.")
                    }
                    putJsonObject("template_values") {
                        put("type", "string")
                        put("description", "JSON-объект подстановок: {\"ключ\": \"значение\"} → {{ключ}}.")
                    }
                    putJsonObject("output_path") {
                        put("type", "string")
                        put("description", "Записать результат в новый файл, а не на месте.")
                    }
                    putJsonObject("file_name") {
                        put("type", "string")
                        put("description", "Имя файла-копии (по умолчанию — Documents/Meha).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл-копию.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "docx_append",
            description = "Дописать текст в конец DOCX новыми абзацами (без форматирования: Markdown-" +
                "разметка сохраняется как обычный текст). По умолчанию правка на месте (с резервной " +
                "копией), либо копия в output_path/file_name.",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к существующему DOCX, например Documents/report.docx.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Текст для добавления; каждая строка — новый абзац.")
                    }
                    putJsonObject("output_path") {
                        put("type", "string")
                        put("description", "Записать результат в новый файл, а не на месте.")
                    }
                    putJsonObject("file_name") {
                        put("type", "string")
                        put("description", "Имя файла-копии (по умолчанию — Documents/Meha).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл-копию.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("content"))
                }
            },
        ),
        Tool(
            name = "pptx_create",
            description = "Создать презентацию PPTX из списка слайдов. `slides` — JSON-массив: " +
                "строки (первая строка — заголовок, остальные — пункты) или объекты " +
                "{\"title\": \"…\", \"lines\": [\"…\"]} / {\"title\": \"…\", \"text\": \"a\\nb\"}. " +
                "Пакет собирается нативно (Office Open XML), кириллица сохраняется.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемому PPTX, например Documents/deck.pptx.")
                    }
                    putJsonObject("slides") {
                        put("type", "string")
                        put("description", "JSON-массив слайдов: строки или {\"title\": …, \"lines\": [...]}.")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("slides"))
                }
            },
        ),
        Tool(
            name = "pptx_edit",
            description = "Изменить существующий PPTX: `mode`=append — добавить слайды (`slides`, тот же " +
                "формат, что у pptx_create); `mode`=replace — заменить текст на слайде(ах): `find` и " +
                "`replace`, необязательный `slide` (с 1; по умолчанию все). Правка на месте с резервной " +
                "копией. Ограничение: замена в пределах одного узла `<a:t>` (текст, разбитый между " +
                "runs, не находится).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к существующему PPTX, например Documents/deck.pptx.")
                    }
                    putJsonObject("mode") {
                        put("type", "string")
                        put("description", "`append` — добавить слайды; `replace` — заменить текст.")
                    }
                    putJsonObject("slides") {
                        put("type", "string")
                        put("description", "Для append: JSON-массив добавляемых слайдов.")
                    }
                    putJsonObject("slide") {
                        put("type", "string")
                        put("description", "Для replace: номер слайда (с 1; по умолчанию все).")
                    }
                    putJsonObject("find") {
                        put("type", "string")
                        put("description", "Для replace: искомый текст.")
                    }
                    putJsonObject("replace") {
                        put("type", "string")
                        put("description", "Для replace: текст замены.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("mode"))
                }
            },
        ),
        Tool(
            name = "odp_create",
            description = "Создать презентацию ODP (OpenDocument) из списка слайдов. `slides` — JSON-" +
                "массив строк или объектов {\"title\": …, \"lines\": [...]}, как у pptx_create.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемому ODP, например Documents/deck.odp.")
                    }
                    putJsonObject("slides") {
                        put("type", "string")
                        put("description", "JSON-массив слайдов: строки или {\"title\": …, \"lines\": [...]}.")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("slides"))
                }
            },
        ),
        Tool(
            name = "xlsx_style",
            description = "Применить базовое форматирование к диапазону ячеек XLSX: `bold` (полужирный), " +
                "`fill_color` (#RRGGBB, заливка), `number_format` (числовой формат). `range` — A1 или " +
                "A1:C3. Правка на месте с резервной копией; значения ячеек не меняются. Честно " +
                "сообщает, если структуру стилей изменить нельзя.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к XLSX, например Documents/table.xlsx.")
                    }
                    putJsonObject("range") {
                        put("type", "string")
                        put("description", "Диапазон ячеек: A1 или A1:C3.")
                    }
                    putJsonObject("sheet") {
                        put("type", "string")
                        put("description", "Имя или индекс листа (по умолчанию первый).")
                    }
                    putJsonObject("bold") {
                        put("type", "boolean")
                        put("description", "Сделать текст полужирным.")
                    }
                    putJsonObject("fill_color") {
                        put("type", "string")
                        put("description", "Цвет заливки #RRGGBB.")
                    }
                    putJsonObject("number_format") {
                        put("type", "string")
                        put("description", "Числовой формат, напр. 0.00 или #,##0.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("range"))
                }
            },
        ),
        Tool(
            name = "office_convert",
            description = "Конвертировать документ между поддерживаемыми форматами по содержимому " +
                "(текст/ячейки), без движка вёрстки. Текст (DOCX/ODT/RTF/TXT/MD/HTML) → TXT/MD/HTML/" +
                "DOCX/ODT/RTF; таблицы (XLSX/ODS/CSV/TSV) → CSV/TSV/JSON/TXT/MD/XLSX/ODS; презентации " +
                "(PPTX/ODP) → TXT/MD. Оформление не сохраняется. Office→PDF не поддерживается — " +
                "используйте pdf_create/pdf_from_markdown.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному документу.")
                    }
                    putJsonObject("target") {
                        put("type", "string")
                        put("description", "Целевой формат (имя или расширение), напр. md, csv, json, xlsx.")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому файлу.")
                    }
                    putJsonObject("sheet") {
                        put("type", "string")
                        put("description", "Лист XLSX/ODS (по умолчанию первый).")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Заголовок документа (метаданные; необязательно).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("target"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),
        Tool(
            name = "office_merge",
            description = "Объединить несколько однотипных документов в один. Текст — конкатенация " +
                "(разделитель `separator`, по умолчанию пустая строка); таблицы — добавление строк " +
                "(со строкой-маркером имени файла); презентации — слайды всех файлов. Формат " +
                "результата — по расширению `destination` (pptx/odp/csv/txt/md и т.п.). Только " +
                "содержимое, оформление не сохраняется.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sources") {
                        put("type", "string")
                        put("description", "Пути к исходным файлам через запятую.")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому файлу.")
                    }
                    putJsonObject("separator") {
                        put("type", "string")
                        put("description", "Разделитель между текстовыми частями (необязательно).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("sources"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),
        Tool(
            name = "office_split",
            description = "Разбить документ на части в каталог `output_dir`. Презентации — по файлу на " +
                "слайд (slide-001.pptx/odp); таблицы — по CSV на лист (sheet-001-*.csv); текст — по " +
                "частям, разделённым строкой `separator` (по умолчанию ---). Существующие файлы " +
                "пропускаются без overwrite. Только содержимое, оформление не сохраняется.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к исходному документу.")
                    }
                    putJsonObject("output_dir") {
                        put("type", "string")
                        put("description", "Каталог для результатов (будет создан при необходимости).")
                    }
                    putJsonObject("separator") {
                        put("type", "string")
                        put("description", "Строка-разделитель для текстовых документов (по умолчанию ---).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать существующие файлы.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("output_dir"))
                }
            },
        ),
        Tool(
            name = "doc_tables",
            description = "Извлечь таблицу (XLSX/ODS/CSV/TSV) как STRUCTURED-данные: JSON-объект " +
                "{\"format\": …, \"rowCount\": N, \"truncated\": bool, \"rows\": [[…]]}, где `rows` — " +
                "массив строк, каждая строка — массив ячеек. `max_rows` ограничивает вывод " +
                "(по умолчанию 1000); флаг `truncated` честно сообщает об обрезке.",
            dangerous = false,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к таблице (XLSX/ODS/CSV/TSV).")
                    }
                    putJsonObject("sheet") {
                        put("type", "string")
                        put("description", "Лист XLSX/ODS (имя или индекс; по умолчанию первый).")
                    }
                    putJsonObject("max_rows") {
                        put("type", "string")
                        put("description", "Максимум строк в выводе (по умолчанию 1000).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
    )
}
