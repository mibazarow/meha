package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- UI automation (accessibility) ----
internal object UiAutomationTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "ui_tree",
            description = "Снять дерево элементов текущего экрана (текст, id, координаты, кликабельность) — чтобы понять, куда нажимать. Работает в любом приложении. Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "screen_understand",
            description = "Понять, что сейчас на экране: слить дерево элементов спецвозможностей с текстом, " +
                "распознанным (OCR) на скриншоте. Дерево даёт структуру, роли и координаты, а OCR " +
                "добавляет текст, который дерево не видит (WebView, canvas, игры, картинки). " +
                "Возвращает элементы и строки, видимые только на изображении. " +
                "Для OCR используется офлайн-движок Tesseract, который распознаёт кириллицу и латиницу; " +
                "если движок не удалось запустить, применяется латинский ML Kit. " +
                "verbose=true добавляет сырые дампы дерева и OCR; attach_image=true прикладывает скриншот " +
                "к запросу модели как изображение, если текущая модель поддерживает vision, иначе скриншот " +
                "сохраняется файлом (доступен через files_read/ocr_image); " +
                "max_chars ограничивает длину ответа. Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("verbose") { put("type", "boolean"); put("description", "Показать сырые дерево и OCR (по умолчанию false)") }
                    putJsonObject("attach_image") { put("type", "boolean"); put("description", "Приложить скриншот к запросу как изображение, если модель поддерживает vision; иначе сохранить файлом (по умолчанию false)") }
                    putJsonObject("max_chars") { put("type", "string"); put("description", "Максимальная длина ответа, 500–20000 (по умолчанию 4000)") }
                }
            },
        ),
        Tool(
            name = "ui_tap",
            description = "Нажать на элемент текущего экрана: по координатам (x,y) или по selector (text/desc/id из ui_tree).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("x") { put("type", "string"); put("description", "Координата X (опционально)") }
                    putJsonObject("y") { put("type", "string"); put("description", "Координата Y (опционально)") }
                    putJsonObject("text") { put("type", "string"); put("description", "Текст элемента (опционально)") }
                    putJsonObject("textContains") { put("type", "string"); put("description", "Подстрока текста") }
                    putJsonObject("desc") { put("type", "string"); put("description", "Описание (contentDescription)") }
                    putJsonObject("contentDescription") { put("type", "string"); put("description", "contentDescription") }
                    putJsonObject("id") { put("type", "string"); put("description", "view id (опционально)") }
                    putJsonObject("resourceId") { put("type", "string"); put("description", "view id или суффикс") }
                    putJsonObject("className") { put("type", "string"); put("description", "Класс элемента") }
                    putJsonObject("index") { put("type", "string"); put("description", "Номер совпадения (с 1).") }
                }
            },
        ),
        Tool(
            name = "ui_long_press",
            description = "Долгое нажатие на элемент (по координатам или selector).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("x") { put("type", "string"); put("description", "Координата X (опционально)") }
                    putJsonObject("y") { put("type", "string"); put("description", "Координата Y (опционально)") }
                    putJsonObject("text") { put("type", "string"); put("description", "Текст элемента") }
                    putJsonObject("textContains") { put("type", "string"); put("description", "Подстрока текста") }
                    putJsonObject("desc") { put("type", "string"); put("description", "contentDescription") }
                    putJsonObject("contentDescription") { put("type", "string"); put("description", "contentDescription") }
                    putJsonObject("id") { put("type", "string"); put("description", "view id") }
                    putJsonObject("resourceId") { put("type", "string"); put("description", "view id или суффикс") }
                    putJsonObject("className") { put("type", "string"); put("description", "Класс элемента") }
                    putJsonObject("index") { put("type", "string"); put("description", "Номер совпадения (с 1).") }
                }
            },
        ),
        Tool(
            name = "ui_swipe",
            description = "Свайп по экрану из (x1,y1) в (x2,y2) с длительностью duration_ms.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("x1") { put("type", "string"); put("description", "Начало X") }
                    putJsonObject("y1") { put("type", "string"); put("description", "Начало Y") }
                    putJsonObject("x2") { put("type", "string"); put("description", "Конец X") }
                    putJsonObject("y2") { put("type", "string"); put("description", "Конец Y") }
                    putJsonObject("duration_ms") { put("type", "string"); put("description", "Длительность, мс") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("x1")); add(JsonPrimitive("y1"))
                    add(JsonPrimitive("x2")); add(JsonPrimitive("y2"))
                }
            },
        ),
        Tool(
            name = "ui_scroll",
            description = "Прокрутить текущий экран. direction: down (по умолчанию) или up.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("direction") { put("type", "string"); put("description", "down | up") } }
            },
        ),
        Tool(
            name = "ui_find",
            description = "Найти элементы на текущем экране БЕЗ вывода всего дерева: по text, " +
                "contentDescription, resourceId и/или className. exact=false (по умолчанию) — вхождение " +
                "без учёта регистра, exact=true — полное совпадение. Возвращает индекс, текст/описание, " +
                "класс, флаги click/edit и bounds — этого достаточно для ui_tap по координатам. " +
                "limit ограничивает число результатов. Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Текст элемента (опционально)") }
                    putJsonObject("textContains") { put("type", "string"); put("description", "Подстрока текста") }
                    putJsonObject("contentDescription") { put("type", "string"); put("description", "contentDescription") }
                    putJsonObject("resourceId") { put("type", "string"); put("description", "view id или суффикс") }
                    putJsonObject("className") { put("type", "string"); put("description", "Имя класса, например android.widget.Button (опционально)") }
                    putJsonObject("index") { put("type", "string"); put("description", "Номер совпадения (с 1).") }
                    putJsonObject("exact") { put("type", "boolean"); put("description", "true — точное совпадение, false — вхождение (по умолчанию false)") }
                    putJsonObject("limit") { put("type", "string"); put("description", "Максимум результатов (по умолчанию 20, максимум 100)") }
                }
            },
        ),
        Tool(
            name = "ui_wait_for_text",
            description = "Ждать появления текста на текущем экране (bounded): опрашивает дерево " +
                "спецвозможностей, пока узел с text/contentDescription не появится или не истечёт timeoutMs. " +
                "Возвращает bounds найденного узла и прошедшее время либо честное «не дождался» за N мс. " +
                "Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Искомый текст (или другой критерий)") }
                    putJsonObject("textContains") { put("type", "string"); put("description", "Подстрока текста") }
                    putJsonObject("contentDescription") { put("type", "string"); put("description", "contentDescription") }
                    putJsonObject("resourceId") { put("type", "string"); put("description", "view id или суффикс") }
                    putJsonObject("className") { put("type", "string"); put("description", "Класс элемента") }
                    putJsonObject("index") { put("type", "string"); put("description", "Номер совпадения (с 1).") }
                    putJsonObject("timeoutMs") { put("type", "string"); put("description", "Таймаут в мс (по умолчанию 8000, максимум 30000)") }
                    putJsonObject("intervalMs") { put("type", "string"); put("description", "Интервал опроса в мс (по умолчанию 400)") }
                    putJsonObject("exact") { put("type", "boolean"); put("description", "true — точное совпадение, false — вхождение (по умолчанию false)") }
                }
            },
        ),
        Tool(
            name = "ui_scroll_until",
            description = "Прокручивать экран, пока на нём не появится нужный текст. direction: down " +
                "(по умолчанию) или up. Bounded: не более maxSwipes свайпов (по умолчанию 8, максимум 30). " +
                "Возвращает bounds найденного узла либо честное «не найдено после N свайпов». " +
                "Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Искомый текст (или другой критерий)") }
                    putJsonObject("textContains") { put("type", "string"); put("description", "Подстрока текста") }
                    putJsonObject("contentDescription") { put("type", "string"); put("description", "contentDescription") }
                    putJsonObject("resourceId") { put("type", "string"); put("description", "view id или суффикс") }
                    putJsonObject("className") { put("type", "string"); put("description", "Класс элемента") }
                    putJsonObject("index") { put("type", "string"); put("description", "Номер совпадения (с 1).") }
                    putJsonObject("direction") { put("type", "string"); put("description", "down | up (по умолчанию down)") }
                    putJsonObject("maxSwipes") { put("type", "string"); put("description", "Максимум свайпов (по умолчанию 8, максимум 30)") }
                    putJsonObject("exact") { put("type", "boolean"); put("description", "true — точное совпадение, false — вхождение (по умолчанию false)") }
                }
            },
        ),
        Tool(
            name = "ui_type",
            description = "Ввести текст в поле: по selector (text/desc/id) или в текущее поле в фокусе.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("description", "Текст для ввода") }
                    putJsonObject("selectorText") { put("type", "string"); put("description", "Текст поля-цели (опционально)") }
                    putJsonObject("textContains") { put("type", "string"); put("description", "Подстрока текста поля (опционально)") }
                    putJsonObject("id") { put("type", "string"); put("description", "view id поля (опционально)") }
                    putJsonObject("resourceId") { put("type", "string"); put("description", "view id поля, полный или суффикс (опционально)") }
                    putJsonObject("desc") { put("type", "string"); put("description", "contentDescription поля") }
                    putJsonObject("contentDescription") { put("type", "string"); put("description", "contentDescription поля") }
                    putJsonObject("className") { put("type", "string"); put("description", "Класс поля (опционально)") }
                    putJsonObject("index") { put("type", "string"); put("description", "Номер совпадения (с 1).") }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        Tool(
            name = "key_event",
            description = "Глобальная системная клавиша: back, home, recents, notifications, " +
                "quick_settings, lock (блокировка экрана, Android 9+/API 28+) и screenshot " +
                "(снимок экрана через спецвозможности, Android 14+/API 34+; на более старых API " +
                "запрос честно отклоняется). Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("key") { put("type", "string"); put("description", "back | home | recents | notifications | quick_settings | lock | screenshot") } }
                putJsonArray("required") { add(JsonPrimitive("key")) }
            },
        ),
        Tool(
            name = "ui_copy",
            description = "Скопировать текущее выделение в буфер обмена (ACTION_COPY). Работает с " +
                "узлом в фокусе: сначала FOCUS_INPUT, затем FOCUS_ACCESSIBILITY. Если выделения " +
                "нет или узел не поддерживает копирование (performAction вернул false) — честная " +
                "ошибка. Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "ui_paste",
            description = "Вставить текст из системного буфера обмена в узел в фокусе (ACTION_PASTE). " +
                "Честная ошибка, если поле не в фокусе или не поддерживает вставку. Требует " +
                "включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "ui_cut",
            description = "Вырезать текущее выделение в буфер обмена (ACTION_CUT). Работает с узлом " +
                "в фокусе. Честная ошибка, если выделения нет или узел не поддерживает вырезание. " +
                "Требует включённой службы спецвозможностей Меха.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "ui_select_all",
            description = "Выделить весь текст в узле в фокусе. Публичного ACTION_SELECT_ALL у " +
                "AccessibilityNodeInfo нет, поэтому диапазон 0..длина текста выделяется " +
                "документированным ACTION_SET_SELECTION с аргументами начала и конца. Если узел " +
                "не отдаёт текст или отклоняет действие — честная ошибка. Требует включённой " +
                "службы спецвозможностей Меха.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "ui_accessibility_status",
            description = "Показать честный статус службы спецвозможностей Меха: включена ли она в " +
                "системе и подключена ли к приложению. Только чтение, разрешение не требуется.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "ui_accessibility_open_settings",
            description = "Открыть системный экран «Специальные возможности», где пользователь " +
                "вручную включает службу Меха. Разрешение не требуется.",
            parameters = buildJsonObject { put("type", "object") },
        ),

        Tool(
            name = "screen_capture",
            description = "Сделать скриншот всего экрана (через службу спецвозможностей, Android 11+) и сохранить в галерею Pictures/Meha. Возвращает путь.",
            parameters = buildJsonObject { put("type", "object") },
        ),

        Tool(
            name = "screen_capture_region",
            description = "Захватить регион или окно экрана (Android 14+) и сохранить результат в файл. " +
                "Без координат снимает весь экран. С параметрами left/top/width/height снимает кадр и " +
                "обрезает его до указанного региона (это честный кроп кадра, а не отдельный захват окна). " +
                "С параметром window/app на Android 14+ (API 34) в системном диалоге можно выбрать одно " +
                "окно приложения (MediaProjectionConfig, выбор делает пользователь). Если задан duration_ms — " +
                "записывает короткое видео этой длительности (только весь экран). Согласие пользователя " +
                "запрашивается на КАЖДУЮ сессию. Действие опасное. Путь в общем хранилище (/storage/emulated/0).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу результата (.png для кадра, .mp4 для видео; в общем хранилище).")
                    }
                    putJsonObject("left") { put("type", "string"); put("description", "Левая граница региона в пикселях (опционально)") }
                    putJsonObject("top") { put("type", "string"); put("description", "Верхняя граница региона в пикселях (опционально)") }
                    putJsonObject("width") { put("type", "string"); put("description", "Ширина региона в пикселях (опционально)") }
                    putJsonObject("height") { put("type", "string"); put("description", "Высота региона в пикселях (опционально)") }
                    putJsonObject("window") { put("type", "string"); put("description", "Захватить одно окно приложения (пакет/имя). Только Android 14+; конкретное окно выбирает пользователь в системном диалоге") }
                    putJsonObject("app") { put("type", "string"); put("description", "Синоним параметра window (пакет приложения)") }
                    putJsonObject("duration_ms") { put("type", "string"); put("description", "Если задано — записать видео этой длительности в мс (только весь экран; максимум 60000)") }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),

        Tool(
            name = "screen_record_start",
            description = "Начать запись экрана (видео H.264/MP4) в указанный файл. Требуется согласие пользователя: системный диалог показывается заново на КАЖДУЮ сессию записи (на Android 14+ токен MediaProjection нельзя переиспользовать). Своего подтверждения приложение не запрашивает. Путь в общем хранилище (/storage/emulated/0).",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу записи (.mp4; в общем хранилище).")
                    }
                    putJsonObject("with_audio") {
                        put("type", "boolean")
                        put("description", "Записывать звук микрофона вместе с экраном (по умолчанию false)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "screen_record_stop",
            description = "Остановить начатую запись экрана и сохранить файл. Возвращает путь и размер готового видео.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "screen_record_status",
            description = "Проверить, идёт ли сейчас запись экрана, и узнать целевой файл и его текущий размер.",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "remember_screen",
            description = "Прочитать видимый текст ТЕКУЩЕГО экрана (приложения под оверлеем Меха) и " +
                "запомнить его в долговременную память. Только чтение: ничего не нажимает. " +
                "Работает, когда Меха вызвана поверх другого приложения (плавающий помощник) — " +
                "берётся окно приложения, а не собственный оверлей Меха. Dedupe подряд идущих " +
                "дублей, порядок чтения сохраняется, длина ограничена max_chars. При save=true " +
                "(по умолчанию) сохраняет заметку с тегами remember_screen и screen:<пакет>; " +
                "note — короткая подпись/заголовок, tags — свои теги через запятую, person — имя " +
                "или id человека (резолвится честно, без угадывания). Требует включённой службы " +
                "спецвозможностей Меха.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("note") { put("type", "string"); put("description", "Короткая подпись/заголовок записи (опционально)") }
                    putJsonObject("tags") { put("type", "string"); put("description", "Свои теги через запятую (опционально)") }
                    putJsonObject("person") { put("type", "string"); put("description", "Имя или id человека для привязки (опционально)") }
                    putJsonObject("save") { put("type", "boolean"); put("description", "Сохранить в память (по умолчанию true)") }
                    putJsonObject("max_chars") { put("type", "string"); put("description", "Максимальная длина текста, 200–20000 (по умолчанию 4000)") }
                }
            },
        ),
    )
}
