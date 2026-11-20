package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- network ----
internal object NetworkTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "http_request",
            description = "Выполнить HTTP-запрос с указанным методом, URL, заголовками и телом. Для любых сетевых запросов к API или веб-страницам.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("method") {
                        put("type", "string")
                        put("description", "HTTP-метод (GET, POST, PUT, DELETE и т.д.)")
                    }
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Адрес запроса (абсолютный URL)")
                    }
                    putJsonObject("headers") {
                        put("type", "object")
                        put("description", "Заголовки запроса в виде пар ключ-значение")
                    }
                    putJsonObject("body") {
                        put("type", "string")
                        put("description", "Тело запроса в виде строки")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("method"))
                    add(JsonPrimitive("url"))
                }
            },
        ),
        Tool(
            name = "download_file",
            description = "Скачать файл по URL на устройство. Используй, когда нужно сохранить ресурс с интернета. По умолчанию файл сохраняется в папку «Загрузки» (Download, то есть /storage/emulated/0/Download) — тогда аргумент destination можно НЕ указывать. Если пользователь явно назвал другое место, укажи его в destination: этот путь приоритетнее дефолта. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Адрес скачиваемого файла (абсолютный URL)")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к директории назначения (в общем хранилище или /storage/emulated/0). По умолчанию — «Загрузки» (Download).")
                    }
                    putJsonObject("filename") {
                        put("type", "string")
                        put("description", "Имя сохраняемого файла (необязательно; иначе из URL/Content-Disposition).")
                    }
                    putJsonObject("max_bytes") {
                        put("type", "string")
                        put("description", "Максимальный размер в байтах (по умолчанию 200 МБ).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("url"))
                }
            },
        ),

        Tool(
            name = "web_fetch",
            description = "Загрузить веб-страницу по URL и быстро вернуть её читаемый текст (по HTTP, без выполнения JavaScript — не блокирует приложение). Используй, чтобы прочитать содержимое сайта/статьи, или чтобы выполнить поиск (запроси поисковый URL вроде https://html.duckduckgo.com/html/?q=запрос). Для страниц, которые рендерятся только JavaScript, или для интерактивной работы используй web_open + web_readable. Не вызывает подтверждение. Не сработает против жёстких анти-ботов (Cloudflare).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Абсолютный URL страницы для загрузки и чтения")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            },
        ),
        Tool(
            name = "web_open",
            description = "Открыть URL во встроенном браузере (WebView) с СОХРАНЯЕМОЙ сессией. В отличие от web_fetch, после открытия можно кликать, вводить текст и т.д. Возвращает URL, заголовок и начало текста. Используй как первый шаг интерактивной работы с сайтом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Абсолютная http/https ссылка")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            },
        ),
        Tool(
            name = "web_click",
            description = "Кликнуть по элементу на текущей открытой странице (после web_open): по CSS-селектору и/или по видимому тексту. Поддерживаются ссылки, кнопки, поля, <label> и связанные с ними контролы, элементы с aria-label и др. Текст ищется сначала точным совпадением (без учёта регистра), затем подстрокой. Возвращает новое состояние страницы.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") {
                        put("type", "string")
                        put("description", "CSS-селектор элемента (например a.login, button#submit) — опционально")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Видимый текст элемента для клика (например «Войти»); ищется в ссылках, кнопках, полях, <label> и связанных контролах — опционально")
                    }
                }
            },
        ),
        Tool(
            name = "web_type",
            description = "Ввести текст в поле на текущей странице (после web_open) по CSS-селектору. Параметр submit=true отправит форму после ввода.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") {
                        put("type", "string")
                        put("description", "CSS-селектор поля ввода (input/textarea)")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Текст для ввода")
                    }
                    putJsonObject("submit") {
                        put("type", "boolean")
                        put("description", "true — отправить форму после ввода")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("selector"))
                    add(JsonPrimitive("text"))
                }
            },
        ),
        Tool(
            name = "web_scroll",
            description = "Прокрутить текущую страницу (после web_open). direction: down (по умолчанию), up, top, bottom; amount — пиксели для down/up (по умолчанию 800).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("direction") {
                        put("type", "string")
                        put("description", "down | up | top | bottom")
                    }
                    putJsonObject("amount") {
                        put("type", "string")
                        put("description", "Количество пикселей для прокрутки (опционально)")
                    }
                }
            },
        ),
        Tool(
            name = "web_back",
            description = "Вернуться на предыдущую страницу во встроенном браузере (история сессии web_*).",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "web_extract",
            description = "Извлечь читаемый текст текущей открытой страницы (после web_open). Используй, чтобы прочитать содержимое без повторной загрузки.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("max_chars") {
                        put("type", "string")
                        put("description", "Максимум символов (опционально, до 20000)")
                    }
                }
            },
        ),
        Tool(
            name = "web_navigate",
            description = "Перейти по URL в текущей веб-сессии (с ожиданием загрузки, до ~25 с). Возвращает итоговый URL и заголовок. Используй после web_open для переходов, когда нужно явно дождаться загрузки.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Абсолютная http/https ссылка")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            },
        ),
        Tool(
            name = "web_search",
            description = "Поиск в интернете, вернуть список результатов (заголовок, ссылка, сниппет). Открывает HTML-выдачу поисковика во встроенном браузере и парсит список; при неудаче честно сообщает, что результатов нет. Не требует подтверждения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Поисковый запрос")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Максимум результатов (опционально, по умолчанию 10, максимум 30)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "image_search",
            description = "Поиск изображений в интернете по CC-лицензиям (Openverse / Wikimedia Commons), вернуть список с ПРЯМЫМИ ссылками на файлы картинок. Нужен, когда агенту нужна именно КАРТИНКА (например, для wallpaper_set / download_file): web_search возвращает ссылки на поисковые страницы, а здесь — прямые URL изображений. Параметры: query (обязательно), count (по умолчанию 5, максимум 10), orientation (landscape / portrait / square, опционально), source (openverse | wikimedia, по умолчанию openverse). Ключи API не требуются. Не требует подтверждения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Поисковый запрос")
                    }
                    putJsonObject("count") {
                        put("type", "string")
                        put("description", "Максимум результатов (опционально, по умолчанию 5, максимум 10)")
                    }
                    putJsonObject("orientation") {
                        put("type", "string")
                        put("description", "Соотношение сторон (опционально): landscape / portrait / square")
                    }
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Источник (опционально): openverse | wikimedia, по умолчанию openverse")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "web_wait",
            description = "Подождать элемент/условие на странице (bounded-таймаут). Если selector не задан — ждёт полной загрузки страницы (readyState=complete).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("selector") {
                        put("type", "string")
                        put("description", "CSS-селектор ожидаемого элемента (опционально)")
                    }
                    putJsonObject("timeout_sec") {
                        put("type", "string")
                        put("description", "Таймаут в секундах (опционально, по умолчанию 10, от 1 до 60)")
                    }
                }
            },
        ),
        Tool(
            name = "web_readable",
            description = "Извлечь основной текст страницы (readability): убирает навигацию/скрипты/стили и возвращает главное содержимое. Используй для чтения статей.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("max_chars") {
                        put("type", "string")
                        put("description", "Максимум символов (опционально, по умолчанию 100000, максимум 300000)")
                    }
                }
            },
        ),
        Tool(
            name = "web_eval_js",
            description = "Выполнить JavaScript на текущей странице и вернуть результат (через evaluateJavascript). Результат обрезается до 20000 символов. Используй осторожно — только на доверенных страницах.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("script") {
                        put("type", "string")
                        put("description", "JavaScript для выполнения на странице")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("script")) }
            },
        ),
        Tool(
            name = "web_screenshot",
            description = "Сохранить скриншот текущей веб-страницы в PNG-файл. Без подтверждения (пишет файл) — только по прямой просьбе пользователя. Путь в общем хранилище (/storage/emulated/0).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к PNG-файлу (в общем хранилище).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "web_find",
            description = "Найти текст на текущей странице: возвращает число совпадений и фрагменты контекста вокруг них.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Искомый текст")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "web_scroll_until",
            description = "Прокручивать страницу до конца (подгрузка контента/Lazy-load), пока высота документа не перестанет расти. Bounded: ограничено max_steps.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("max_steps") {
                        put("type", "string")
                        put("description", "Максимум шагов прокрутки (опционально, по умолчанию 20, максимум 50)")
                    }
                }
            },
        ),
        Tool(
            name = "web_tabs_new",
            description = "Создать новую вкладку веб-сессии (собственный WebView и история) и сделать её активной. Можно сразу указать url для загрузки.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "URL для загрузки в новой вкладке (опционально)")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя вкладки для переключения (опционально)")
                    }
                }
            },
        ),
        Tool(
            name = "web_tabs_switch",
            description = "Переключить активную вкладку веб-сессии по index (с нуля, как в web_tabs_list) или по name.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("index") {
                        put("type", "string")
                        put("description", "Индекс вкладки с нуля (опционально)")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя вкладки (опционально)")
                    }
                }
            },
        ),
        Tool(
            name = "web_tabs_close",
            description = "Закрыть вкладку веб-сессии по index или name. Без параметров закрывает активную вкладку.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("index") {
                        put("type", "string")
                        put("description", "Индекс вкладки с нуля (опционально)")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя вкладки (опционально)")
                    }
                }
            },
        ),
        Tool(
            name = "web_tabs_list",
            description = "Показать список вкладок веб-сессии: индекс, имя, URL и какая вкладка активна.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "web_session_save",
            description = "Сохранить cookie-сессию текущей страницы (CookieManager) в приватное хранилище приложения под именем name. Используй для сценариев с логином.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя сессии, например mysite")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("name")) }
            },
        ),
        Tool(
            name = "web_session_load",
            description = "Восстановить cookie-сессию по имени name (CookieManager.setCookie + flush). После восстановления открой страницу через web_navigate.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя ранее сохранённой сессии")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("name")) }
            },
        ),
        Tool(
            name = "web_forward",
            description = "Перейти вперёд по истории встроенного браузера (после web_back). Проверяет, есть ли куда идти.",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "web_reload",
            description = "Перезагрузить текущую страницу веб-сессии (после web_open/web_navigate).",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "web_submit",
            description = "Отправить форму на текущей странице: отправляет форму активного поля ввода, иначе первую форму страницы. Осторожно — возможны побочные эффекты (заказ, публикация).",
            parameters = buildJsonObject {
                put("type", "object")
            },
        ),
        Tool(
            name = "web_set_user_agent",
            description = "Установить User-Agent веб-сессии (web_set_user_agent). Пустое значение ua возвращает User-Agent по умолчанию. Смена UA может ломать сайты — используй осторожно.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("ua") {
                        put("type", "string")
                        put("description", "Строка User-Agent; пустая строка — вернуть значение по умолчанию")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("ua")) }
            },
        ),
        Tool(
            name = "web_download",
            description = "Скачать файл по URL из веб-сессии через системный менеджер загрузок. Файл сохраняется в общую папку «Загрузки»; инструмент честно сообщает результат (завершено/в очереди/ошибка).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "Абсолютная http/https ссылка на файл")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            },
        ),
    )
}
