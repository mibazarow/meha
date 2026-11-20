package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- contacts ----
internal object ContactsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "contact_search",
            description = "Поиск контактов по имени (частичное совпадение). Возвращает id, имя и телефон. Используй перед get/update, чтобы найти id.",
            needsPermission = "android.permission.READ_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Текст поиска по имени контакта")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "contact_get",
            description = "Получить имя, телефоны и email контакта по его id. id берётся из contact_search.",
            needsPermission = "android.permission.READ_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Идентификатор контакта")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "contact_create",
            description = "Создать контакт с именем и телефоном (email опционально).",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя нового контакта")
                    }
                    putJsonObject("phone") {
                        put("type", "string")
                        put("description", "Номер телефона контакта")
                    }
                    putJsonObject("email") {
                        put("type", "string")
                        put("description", "Электронный адрес контакта (опционально)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("name"))
                    add(JsonPrimitive("phone"))
                }
            },
        ),
        Tool(
            name = "contact_update",
            description = "Обновить существующий контакт по id. Передай только те поля, которые нужно изменить: name, phone, email.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Идентификатор обновляемого контакта (из contact_search)")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Новое имя контакта (опционально)")
                    }
                    putJsonObject("phone") {
                        put("type", "string")
                        put("description", "Новый номер телефона (опционально)")
                    }
                    putJsonObject("email") {
                        put("type", "string")
                        put("description", "Новый email (опционально)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "contact_delete",
            description = "Удалить контакт по id (id берётся из contact_search). Необратимо, без подтверждения — только по прямой просьбе пользователя.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Идентификатор удаляемого контакта (из contact_search)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "contact_lookup_by_number",
            description = "Найти контакт по номеру телефона. Возвращает имя и ссылку на фото.",
            needsPermission = "android.permission.READ_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("number") {
                        put("type", "string")
                        put("description", "Номер телефона для поиска контакта")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("number")) }
            },
        ),
        Tool(
            name = "contact_groups_list",
            description = "Список групп (меток) контактов: id, название и аккаунт.",
            needsPermission = "android.permission.READ_CONTACTS",
            parameters = buildJsonObject { put("type", "object") },
        ),
        Tool(
            name = "contact_group_create",
            description = "Создать группу (метку) контактов с указанным названием и вернуть её id. account_type и account_name опциональны: без них группа создаётся в локальном или первом доступном аккаунте.",
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Название новой группы")
                    }
                    putJsonObject("account_type") {
                        put("type", "string")
                        put("description", "Тип аккаунта группы (опционально, указывайте вместе с account_name)")
                    }
                    putJsonObject("account_name") {
                        put("type", "string")
                        put("description", "Имя аккаунта группы (опционально, указывайте вместе с account_type)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("title")) }
            },
        ),
        Tool(
            name = "contact_group_assign",
            description = "Добавить контакт в группу (метку) или убрать из неё. Контакт задаётся contact_id (из contact_search) либо contact_lookup (номер телефона), группа — group_id (из contact_groups_list) либо group_title. remove=true убирает из группы, по умолчанию добавляет.",
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("contact_id") {
                        put("type", "string")
                        put("description", "Контакт (id из contact_search)")
                    }
                    putJsonObject("contact_lookup") {
                        put("type", "string")
                        put("description", "Номер телефона контакта (альтернатива contact_id)")
                    }
                    putJsonObject("group_id") {
                        put("type", "string")
                        put("description", "Группа (id из contact_groups_list)")
                    }
                    putJsonObject("group_title") {
                        put("type", "string")
                        put("description", "Название группы (альтернатива group_id)")
                    }
                    putJsonObject("remove") {
                        put("type", "boolean")
                        put("description", "true — убрать из группы, false (по умолчанию) — добавить")
                    }
                }
            },
        ),
        Tool(
            name = "contact_birthdays",
            description = "Список ближайших дней рождения контактов в окне days дней (по умолчанию 30). Возвращает id, имя, дату (ММ-ДД) и сколько дней осталось; отсортировано по близости. Только чтение.",
            needsPermission = "android.permission.READ_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("days") {
                        put("type", "string")
                        put("description", "Ширина окна в днях (по умолчанию 30)")
                    }
                }
            },
        ),
        Tool(
            name = "contact_photo_get",
            description = "Получить ссылку на фото контакта по его id. Сообщает, есть ли у контакта фото.",
            needsPermission = "android.permission.READ_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("contact_id") {
                        put("type", "string")
                        put("description", "Контакт (id из contact_search)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("contact_id")) }
            },
        ),
        Tool(
            name = "contact_star_set",
            description = "Добавить контакт в избранное или убрать из него по id. Действие изменяет данные контакта.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("contact_id") {
                        put("type", "string")
                        put("description", "Контакт (id из contact_search)")
                    }
                    putJsonObject("starred") {
                        put("type", "boolean")
                        put("description", "true — добавить в избранное (по умолчанию), false — убрать")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("contact_id")) }
            },
        ),
        Tool(
            name = "contact_batch_upsert",
            description = "Пакетно создать/обновить контакты одной транзакцией. names — имена через запятую; phones и emails — необязательные списки через запятую, выровненные по индексу с names. Существующему контакту новые телефон/email добавляются только если их ещё нет. Действие изменяет данные контактов.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("names") {
                        put("type", "string")
                        put("description", "Имена контактов через запятую")
                    }
                    putJsonObject("phones") {
                        put("type", "string")
                        put("description", "Телефоны через запятую, выровненные по индексу с names (необязательно)")
                    }
                    putJsonObject("emails") {
                        put("type", "string")
                        put("description", "Email-адреса через запятую, выровненные по индексу с names (необязательно)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("names")) }
            },
        ),
        Tool(
            name = "contact_merge",
            description = "Слить два контакта в один через правило агрегации. contact_id1 и contact_id2 — id из contact_search. Действие опасное: изменяет связи между контактами.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("contact_id1") {
                        put("type", "string")
                        put("description", "Первый контакт (id из contact_search)")
                    }
                    putJsonObject("contact_id2") {
                        put("type", "string")
                        put("description", "Второй контакт (id из contact_search)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("contact_id1"))
                    add(JsonPrimitive("contact_id2"))
                }
            },
        ),
        Tool(
            name = "contact_split",
            description = "Разделить два контакта, которые агрегированы в один. contact_id1 и contact_id2 — id из contact_search. Действие опасное: изменяет связи между контактами.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("contact_id1") {
                        put("type", "string")
                        put("description", "Первый контакт (id из contact_search)")
                    }
                    putJsonObject("contact_id2") {
                        put("type", "string")
                        put("description", "Второй контакт (id из contact_search)")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("contact_id1"))
                    add(JsonPrimitive("contact_id2"))
                }
            },
        ),
        Tool(
            name = "contact_photo_set",
            description = "Установить фото контакта из файла. contact_id — id из contact_search, photo_path — путь в общем хранилище (/storage/emulated/0). Действие изменяет данные контакта и читает файл.",
            dangerous = true,
            needsPermission = "android.permission.MANAGE_EXTERNAL_STORAGE",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("contact_id") {
                        put("type", "string")
                        put("description", "Контакт (id из contact_search)")
                    }
                    putJsonObject("photo_path") {
                        put("type", "string")
                        put("description", "Путь к файлу изображения (в общем хранилище).")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("contact_id"))
                    add(JsonPrimitive("photo_path"))
                }
            },
        ),
        Tool(
            name = "contact_photo_clear",
            description = "Удалить фото контакта по contact_id. Действие опасное: безусловно удаляет существующее фото.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("contact_id") {
                        put("type", "string")
                        put("description", "Контакт (id из contact_search)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("contact_id")) }
            },
        ),
        Tool(
            name = "contact_export_vcf",
            description = "Экспортировать контакты в файл vCard (.vcf). contact_id опционален: без него выгружаются все контакты. path — опциональный абсолютный путь к .vcf внутри разрешённого хранилища; по умолчанию файл сохраняется в служебную папку exports. Возвращает реальный путь и число экспортированных контактов. Только чтение контактов.",
            needsPermission = "android.permission.READ_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("contact_id") {
                        put("type", "string")
                        put("description", "Идентификатор контакта (из contact_search); без него экспортируются все")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Абсолютный путь к .vcf внутри разрешённого хранилища (опционально)")
                    }
                }
            },
        ),
        Tool(
            name = "contact_import_vcf",
            description = "Импортировать контакты из файла vCard 3.0 (.vcf) в локальную телефонную книгу. path — обязательный путь к .vcf. Поддерживаются имя (FN/N), телефоны (TEL) и email (EMAIL); остальные свойства vCard игнорируются. Возвращает число импортированных и пропущенных карточек. Действие изменяет контакты.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Путь к файлу .vcf внутри разрешённого хранилища")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "contact_group_delete",
            description = "Удалить группу (метку) контактов по group_id (из contact_groups_list). Необратимо, без подтверждения.",
            dangerous = true,
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("group_id") {
                        put("type", "string")
                        put("description", "Группа (id из contact_groups_list)")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("group_id")) }
            },
        ),
        Tool(
            name = "contact_group_rename",
            description = "Переименовать группу (метку) контактов: group_id (из contact_groups_list) и новое title. Действие изменяет данные контактов.",
            needsPermission = "android.permission.WRITE_CONTACTS",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("group_id") {
                        put("type", "string")
                        put("description", "Группа (id из contact_groups_list)")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Новое название группы")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("group_id"))
                    add(JsonPrimitive("title"))
                }
            },
        ),
    )
}
