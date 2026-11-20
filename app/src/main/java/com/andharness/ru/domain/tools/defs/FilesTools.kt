package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- files ----
internal object FilesTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "files_list",
            description = "Список файлов и подкаталогов в указанной директории. Передай существующий path, чтобы прочитать содержимое папки. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к директории (в общем хранилище или /storage/emulated/0).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_read",
            description = "Прочитать содержимое текстового файла по пути. Используй для чтения логов, конфигов и любого текстового содержимого. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("offset") {
                        put("type", "string")
                        put("description", "Смещение в байтах, с которого начать чтение (необязательно).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько байт прочитать (необязательно; по умолчанию 1 МБ, максимум 64 МБ).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_write",
            description = "Записать содержимое в файл (создаст файл, если его нет). Перепишет существующий файл полностью. Необратимо, без подтверждения — только по прямой просьбе пользователя. Путь в общем хранилище (/storage/emulated/0).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Текстовое содержимое для записи в файл")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать файл (по умолчанию true).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("content"))
                }
            },
        ),
        Tool(
            name = "files_create_dir",
            description = "Создать директорию (включая промежуточные папки), если она не существует. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к создаваемой директории (в общем хранилище или /storage/emulated/0).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_delete",
            description = "Удалить один или несколько файлов/директорий. Для папки удаляются всё её содержимое и подпапки (рекурсивно). Необратимо, без подтверждения — только по прямой просьбе пользователя. Путь в общем хранилище (/storage/emulated/0). Несколько путей можно перечислить в одном вызове через запятую или точку с запятой (в path или paths).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу или директории для удаления. Можно перечислить несколько путей одной строкой через запятую или точку с запятой.")
                    }
                    putJsonObject("paths") {
                        put("type", "string")
                        put("description", "То же, что path: один или несколько путей через запятую или точку с запятой.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_move",
            description = "Переместить файл или директорию из source в destination. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Путь к источнику (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь назначения (в общем хранилище или /storage/emulated/0).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("source"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),
        Tool(
            name = "files_rename",
            description = "Переименовать файл или папку в пределах того же каталога. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Текущий путь (в общем хранилище).")
                    }
                    putJsonObject("new_name") {
                        put("type", "string")
                        put("description", "Новое имя (без разделителей пути).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("new_name"))
                }
            },
        ),
        Tool(
            name = "files_copy",
            description = "Скопировать файл или директорию из source в destination. destination — конечный путь (файл или папка). Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Путь к исходному файлу или директории")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь назначения (в общем хранилище или /storage/emulated/0).")
                    }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Перезаписать существующее назначение (по умолчанию false — безопасно).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("source"))
                    add(JsonPrimitive("destination"))
                }
            },
        ),
        Tool(
            name = "files_stat",
            description = "Получить информацию о файле или директории: размер, дата изменения, является ли директорией. Полезно перед операциями с файлом. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь (в общем хранилище или /storage/emulated/0).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),

        Tool(
            name = "files_dir_size",
            description = "Посчитать суммарный размер файлов в папке и показать свободное место на томе.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к папке или файлу (в общем хранилище).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),

        Tool(
            name = "files_open",
            description = "Открыть файл подходящим приложением БЕЗ системного окна выбора: приложение подбирается само (сохранённый выбор для этого типа → системное по умолчанию → лучшее совпадение), опционально задаётся пакетом app. Если ни одно приложение не умеет открывать такой тип, вернёт честное сообщение и список приложений из Google Play. Для папки открывает файловый менеджер. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь (в общем хранилище).")
                    }
                    putJsonObject("app") {
                        put("type", "string")
                        put("description", "Необязательно: пакет приложения, которым открыть файл (переопределяет авто-выбор).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),

        Tool(
            name = "files_append",
            description = "Дописать текст в конец файла (создаёт файл, если его нет). Без подтверждения — только по прямой просьбе пользователя. Путь в общем хранилище (/storage/emulated/0).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к файлу") }
                    putJsonObject("content") { put("type", "string"); put("description", "Текст для добавления") }
                }
                putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("content")) }
            },
        ),
        Tool(
            name = "files_search",
            description = "Найти файлы и папки по имени (подстрока, без учёта регистра) начиная с папки path. Возвращает пути.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "Подстрока имени файла/папки") }
                    putJsonObject("path") { put("type", "string"); put("description", "Папка для поиска (по умолчанию корень хранилища)") }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "files_grep",
            description = "Найти строки, содержащие подстроку, в текстовых файлах (поиск по содержимому).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "Искомая подстрока (без учёта регистра).") }
                    putJsonObject("path") { put("type", "string"); put("description", "Каталог для поиска (по умолчанию корень хранилища).") }
                    putJsonObject("ext") { put("type", "string"); put("description", "Фильтр по расширению, например txt (необязательно).") }
                    putJsonObject("limit") { put("type", "string"); put("description", "Максимум совпадений (по умолчанию 200).") }
                    putJsonObject("max_file_bytes") { put("type", "string"); put("description", "Пропускать файлы больше этого размера (по умолчанию 1 МБ).") }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "files_hash",
            description = "Посчитать хеш файла (MD5, SHA-1 или SHA-256) для проверки целостности/сравнения.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к файлу") }
                    putJsonObject("algorithm") {
                        put("type", "string")
                        put("description", "Алгоритм хеша: MD5, SHA-1 или SHA-256 (по умолчанию SHA-256).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_duplicates",
            description = "Найти дубликаты файлов по размеру и хешу (SHA-256).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Каталог для поиска (по умолчанию корень хранилища).") }
                    putJsonObject("min_size") { put("type", "string"); put("description", "Минимальный размер файла в байтах (по умолчанию 1).") }
                    putJsonObject("limit") { put("type", "string"); put("description", "Максимум групп дубликатов в выводе (по умолчанию 50).") }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "files_zip",
            description = "Упаковать файлы/папки в ZIP-архив. sources — пути через запятую.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("sources") { put("type", "string"); put("description", "Пути источников через запятую") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Путь к создаваемому .zip") }
                }
                putJsonArray("required") { add(JsonPrimitive("sources")); add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "files_unzip",
            description = "Распаковать ZIP-архив в папку назначения.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "string"); put("description", "Путь к .zip") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Папка для распаковки") }
                }
                putJsonArray("required") { add(JsonPrimitive("source")); add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "files_zip_list",
            description = "Показать содержимое ZIP-архива без распаковки.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к ZIP-архиву (в общем хранилище).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Максимум записей для вывода (по умолчанию 200).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_gzip",
            description = "Сжать файл в GZIP.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "string"); put("description", "Путь к сжимаемому файлу") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Путь к создаваемому .gz") }
                }
                putJsonArray("required") { add(JsonPrimitive("source")); add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "files_gunzip",
            description = "Распаковать GZIP-файл.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "string"); put("description", "Путь к .gz-файлу") }
                    putJsonObject("destination") { put("type", "string"); put("description", "Путь к распакованному файлу") }
                }
                putJsonArray("required") { add(JsonPrimitive("source")); add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "share_file",
            description = "Поделиться файлом через системное меню «Поделиться» (отправить в другое приложение).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") { put("type", "string"); put("description", "Путь к файлу") }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),

        Tool(
            name = "share_files",
            description = "Поделиться несколькими файлами через системное меню.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("paths") {
                        put("type", "string")
                        put("description", "Пути к файлам через запятую (в общем хранилище).")
                    }
                    putJsonObject("mime") {
                        put("type", "string")
                        put("description", "MIME-тип, например image/* (по умолчанию */* ).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("paths")) }
            },
        ),

        Tool(
            name = "print_file",
            description = "Отправить файл на печать через системный сервис печати.",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу для печати (в общем хранилище).")
                    }
                    putJsonObject("mime") {
                        put("type", "string")
                        put("description", "MIME-тип файла (необязательно; иначе определяется по расширению).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_split",
            description = "Разбить файл на части: равного размера (parts — число частей) или по размеру части (part_size, например 100MB). Части называются «имя.partNN» и сохраняются в destination_dir (по умолчанию — папка исходного файла). Исходный файл не изменяется; существующие части не перезаписываются. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Путь к исходному файлу (в общем хранилище).")
                    }
                    putJsonObject("parts") {
                        put("type", "string")
                        put("description", "Число частей (целое ≥ 1). Укажите parts или part_size.")
                    }
                    putJsonObject("part_size") {
                        put("type", "string")
                        put("description", "Размер одной части: байты или с суффиксом, например 100MB или 104857600. Укажите parts или part_size.")
                    }
                    putJsonObject("destination_dir") {
                        put("type", "string")
                        put("description", "Папка для частей (необязательно; по умолчанию — папка исходного файла).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("source")) }
            },
        ),
        Tool(
            name = "files_join",
            description = "Собрать части «имя.partNN» обратно в один файл. Укажите parts (пути частей через запятую) или source (любую часть — остальные найдутся автоматически и упорядочатся по номеру). Проверяет, что все части существуют; опционально сверяет expected_size. Существующее назначение не перезаписывается. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("parts") {
                        put("type", "string")
                        put("description", "Пути частей через запятую или точку с запятой.")
                    }
                    putJsonObject("source") {
                        put("type", "string")
                        put("description", "Любая одна часть — остальные части того же имени ищутся и упорядочиваются автоматически.")
                    }
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Путь к создаваемому объединённому файлу (в общем хранилище).")
                    }
                    putJsonObject("expected_size") {
                        put("type", "string")
                        put("description", "Ожидаемый суммарный размер в байтах (необязательно; проверяется до записи).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("destination")) }
            },
        ),
        Tool(
            name = "files_mime",
            description = "Определить MIME-тип файла по сигнатуре (магическим байтам) и расширению. Возвращает тип, уверенность (высокая/средняя/низкая), расширение и основание. Путь в общем хранилище (/storage/emulated/0).",
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
            name = "files_compare",
            description = "Сравнить два файла побайтово: идентичны ли они, иначе — размеры и смещение первого различия; для текстовых файлов — краткая построчная разница (ограниченное число строк). Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path1") {
                        put("type", "string")
                        put("description", "Путь к первому файлу (в общем хранилище).")
                    }
                    putJsonObject("path2") {
                        put("type", "string")
                        put("description", "Путь ко второму файлу (в общем хранилище).")
                    }
                    putJsonObject("max_lines") {
                        put("type", "string")
                        put("description", "Максимум строк построчной разницы (по умолчанию 40).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("path1"))
                    add(JsonPrimitive("path2"))
                }
            },
        ),
        Tool(
            name = "files_hide",
            description = "Скрыть или показать файл/папку в MediaStore. Папка скрывается созданием пустого «.nomedia» (при recursive — во всех подпапках), файл — добавлением/снятием ведущей точки в имени. Действует только на то, что приложение вправе изменить; честно сообщает об ограничениях scoped storage. Путь в общем хранилище (/storage/emulated/0).",
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу или папке (в общем хранилище).")
                    }
                    putJsonObject("hide") {
                        put("type", "boolean")
                        put("description", "true — скрыть (по умолчанию), false — показать.")
                    }
                    putJsonObject("recursive") {
                        put("type", "boolean")
                        put("description", "Для папки: обработать также все подпапки (по умолчанию false).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "files_chmod",
            description = "Изменить права доступа (чтение/запись/выполнение) файла или папки — по возможности. Возвращает фактический результат для каждого флага. На Android под scoped storage обычно можно менять права только своих файлов; для чужих файлов вернётся отказ (отказ никогда не выдаётся за успех). Путь в общем хранилище (/storage/emulated/0).",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу или папке (в общем хранилище).")
                    }
                    putJsonObject("readable") {
                        put("type", "boolean")
                        put("description", "Разрешить/запретить чтение (необязательно).")
                    }
                    putJsonObject("writable") {
                        put("type", "boolean")
                        put("description", "Разрешить/запретить запись (необязательно).")
                    }
                    putJsonObject("executable") {
                        put("type", "boolean")
                        put("description", "Разрешить/запретить выполнение (необязательно).")
                    }
                    putJsonObject("apply_to_all") {
                        put("type", "boolean")
                        put("description", "Применить ко всем пользователям (true, по умолчанию) или только владельцу (false).")
                    }
                    putJsonObject("recursive") {
                        put("type", "boolean")
                        put("description", "Для папки: применить также ко всему содержимому (по умолчанию false).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
    )
}
