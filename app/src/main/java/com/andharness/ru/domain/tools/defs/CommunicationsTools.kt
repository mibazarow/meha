package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- Communications batch 1: local recordings, transcripts, unified search and a person brief.
// Only string/boolean/object schema types (numbers are passed as strings) — the local llama.cpp
// server rejects number/integer/array (see ToolDefinitionInvariantsTest). ----
internal object CommunicationsTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "recordings_list",
            description = "Список локальных записей (диктофон, голосовые сообщения, импорт) с " +
                "длительностью и статусом расшифровки. Необязательные limit (по умолчанию 20, до 200) " +
                "и status (PENDING, RUNNING, DONE, FAILED, SKIPPED). Только чтение, ничего не меняет; " +
                "аудиофайлы лежат на диске, в базу попадают лишь метаданные. Это аудиофайлы — " +
                "телефонные звонки не записываются (Android этого не позволяет).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько записей вернуть, 1–200 (по умолчанию 20).")
                    }
                    putJsonObject("status") {
                        put("type", "string")
                        put(
                            "description",
                            "Необязательный фильтр по статусу расшифровки: PENDING, RUNNING, DONE, " +
                                "FAILED или SKIPPED."
                        )
                    }
                }
            },
        ),
        Tool(
            name = "recording_transcribe",
            description = "Запустить распознавание речи (whisper.cpp) для записи по её id. " +
                "language — код языка или auto (по умолчанию auto). model — base (по умолчанию, " +
                "~141 МБ) или small (точнее, ~465 МБ). Модель должна быть уже скачана: если её нет, " +
                "инструмент честно сообщит об этом и ничего не сделает. В ответе — число сегментов и " +
                "длительность; расшифровка заменяет прежнюю для этой записи. Работает только с " +
                "аудиофайлами; телефонные звонки записать нельзя (Android этого не позволяет).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "id записи (обязательно).")
                    }
                    putJsonObject("language") {
                        put("type", "string")
                        put("description", "Язык распознавания или auto (по умолчанию auto).")
                    }
                    putJsonObject("model") {
                        put("type", "string")
                        put("description", "Модель распознавания: base или small (по умолчанию base).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "transcript_get",
            description = "Показать расшифровку записи по её id: сегменты с таймкодами [начало-конец] " +
                "и говорящим — «Я» для владельца устройства, имя человека, если голос привязан к " +
                "персоне, иначе «Говорящий N» (пока диаризация не выполнена, говорящий может быть не " +
                "определён). Если расшифровки ещё нет, честно сообщит статус и предложит запустить " +
                "recording_transcribe.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("recording_id") {
                        put("type", "string")
                        put("description", "id записи (обязательно).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("recording_id")) }
            },
        ),
        Tool(
            name = "transcript_search",
            description = "Полнотекстовый поиск по текстам расшифровок (FTS по сегментам). query — " +
                "слова для поиска (обязательно), limit — сколько совпадений вернуть (по умолчанию 20, " +
                "до 100). Возвращает id записи, таймкод и текст найденных фрагментов. Ищутся только " +
                "уже расшифрованные записи.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Что искать в текстах расшифровок (обязательно).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько совпадений вернуть, 1–100 (по умолчанию 20).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "communication_search",
            description = "Единый поиск по коммуникационному следу: расшифровки записей, " +
                "взаимодействия из персональной базы (SMS, звонки, мессенджеры, почта, чаты) и, " +
                "по возможности, память. Когда канал неясен («мы где-то это обсуждали», «кто писал " +
                "про…»), вызывай этот инструмент, а не угадывай. query — обязательный запрос, " +
                "limit — ограничение выдачи (по умолчанию 30, до 100). Возвращает один список, " +
                "упорядоченный по времени (новые сверху), с пометкой канала. Только чтение.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Поисковый запрос (обязательно).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько записей вернуть, 1–100 (по умолчанию 30).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "message_search",
            description = "Основной инструмент для вопросов о прошлых сообщениях и переписках: " +
                "гибридный поиск по ПОЛНОМУ тексту сохранённых сообщений (SMS/MMS и перехваченные " +
                "сообщения мессенджеров/почты/чатов) — лексический FTS плюс, при доступных " +
                "офлайн-эмбеддингах, поиск ПО СМЫСЛУ. Вызывай до ответа, если пользователь " +
                "спрашивает, о чём договаривались/кто писал/что было в переписке; semantic=false — " +
                "только для точной цитаты, номера, кода или id. query — обязательный запрос; " +
                "limit — сколько находок вернуть (по умолчанию 20, до 100); source — фильтр " +
                "источника sms|mms|messenger|email|chat (или all); person — имя или числовой id " +
                "человека (резолв никогда не угадывает: при неоднозначности вернёт кандидатов); " +
                "semantic=false — только лексический поиск (по умолчанию true). Возвращает " +
                "отправителя, время, источник, беседу и фрагмент текста до 300 символов. Только " +
                "чтение, ничего не меняет; ищутся только уже сохранённые сообщения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Что искать в текстах сообщений (обязательно).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько находок вернуть, 1–100 (по умолчанию 20).")
                    }
                    putJsonObject("source") {
                        put("type", "string")
                        put(
                            "description",
                            "Фильтр источника: sms, mms, messenger, email, chat или all " +
                                "(по умолчанию all)."
                        )
                    }
                    putJsonObject("person") {
                        put("type", "string")
                        put("description", "Имя или числовой id человека для фильтра (необязательно).")
                    }
                    putJsonObject("semantic") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Искать по смыслу, если доступны эмбеддинги (по умолчанию true); " +
                                "false — только лексический поиск."
                        )
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        Tool(
            name = "messages_summarize",
            description = "Краткое резюме сохранённых сообщений (SMS/MMS и перехваченные сообщения " +
                "мессенджеров/почты/чатов): что обсуждали и о чём договорились. topic — свободный " +
                "текст, что именно резюмировать (необязательно; при задании подбираются релевантные " +
                "сообщения гибридным поиском). person — имя или числовой id человека (резолв никогда " +
                "не угадывает: при неоднозначности вернёт кандидатов и ничего не резюмирует). source — " +
                "фильтр источника sms|mms|messenger|email|chat или all. window — окно today, 7d, 30d " +
                "или all (по умолчанию 30d). limit — сколько сообщений учесть (по умолчанию 40, до " +
                "200). Требуется настроенная модель; без неё инструмент честно сообщит об этом и " +
                "ничего не выдумает. save=true — дополнительно сохранить резюме в память (тег " +
                "messages_summary). Сообщения только читаются; при save=true добавляется запись в память.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("topic") {
                        put("type", "string")
                        put("description", "Что именно резюмировать, свободным текстом (необязательно).")
                    }
                    putJsonObject("person") {
                        put("type", "string")
                        put("description", "Имя или числовой id человека для фильтра (необязательно).")
                    }
                    putJsonObject("source") {
                        put("type", "string")
                        put(
                            "description",
                            "Фильтр источника: sms, mms, messenger, email, chat или all " +
                                "(по умолчанию all)."
                        )
                    }
                    putJsonObject("window") {
                        put("type", "string")
                        put("description", "Окно: today, 7d, 30d или all (по умолчанию 30d).")
                    }
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько сообщений учесть, 1–200 (по умолчанию 40).")
                    }
                    putJsonObject("save") {
                        put("type", "boolean")
                        put("description", "Сохранить резюме в память (по умолчанию false).")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "person_brief",
            description = "Краткая сводка по человеку за период: недавние взаимодействия " +
                "(звонки/SMS/мессенджеры), моменты из расшифровок его записей и связанные " +
                "воспоминания/мнения. person — имя или числовой id (обязательно), days — окно в днях " +
                "(по умолчанию 30). Резолв никогда не угадывает: при неоднозначности вернёт " +
                "кандидатов и попросит выбрать. Только чтение.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("person") {
                        put("type", "string")
                        put("description", "Имя или числовой id человека (обязательно).")
                    }
                    putJsonObject("days") {
                        put("type", "string")
                        put("description", "Окно в днях, 1–365 (по умолчанию 30).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("person")) }
            },
        ),
        Tool(
            name = "speaker_registry_list",
            description = "Показать реестр голосов (говорящих), найденных в записях: id, имя " +
                "(или «Я» для владельца, иначе «Говорящий N»), привязка к человеку, число голосовых " +
                "сэмплов и время обновления. limit — сколько строк вернуть (по умолчанию 50, до 200). " +
                "Только чтение, ничего не меняет. Если реестр пуст, честно сообщит об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько говорящих вернуть, 1–200 (по умолчанию 50).")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "speaker_name_assign",
            description = "Назвать голос, привязать его к человеку из персональной базы или отметить " +
                "как «это я». speaker_id — обязательный id говорящего. name — присвоить имя. " +
                "person — имя или числовой id человека; резолв никогда не угадывает: при " +
                "неоднозначности вернёт кандидатов и попросит выбрать (изменения тогда не вносятся). " +
                "self=true/false — отметить или снять отметку «это я». Можно указать несколько " +
                "параметров сразу; без них инструмент честно сообщит, что менять нечего.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("speaker_id") {
                        put("type", "string")
                        put("description", "id говорящего (обязательно).")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Имя, которое присвоить голосу (необязательно).")
                    }
                    putJsonObject("person") {
                        put("type", "string")
                        put("description", "Имя или числовой id человека для привязки (необязательно).")
                    }
                    putJsonObject("self") {
                        put("type", "boolean")
                        put("description", "Отметить голос как «это я» (true) или снять отметку (false).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("speaker_id")) }
            },
        ),
        Tool(
            name = "speaker_registry_forget",
            description = "Удалить говорящего из реестра вместе со всеми его голосовыми эмбеддингами " +
                "(сэмплами). speaker_id — обязательный id. Действие необратимо; в ответе честно " +
                "сообщается, что именно удалено (имя и число сэмплов). Удаляется только голосовой " +
                "кластер — персоны и записи не трогаются.",
            dangerous = true,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("speaker_id") {
                        put("type", "string")
                        put("description", "id говорящего для удаления (обязательно).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("speaker_id")) }
            },
        ),
        Tool(
            name = "recordings_scan",
            description = "Найти аудиозаписи на устройстве через медиатеку и зарегистрировать их " +
                "для расшифровки. Просматривает аудио из MediaStore (нужно разрешение на доступ к " +
                "аудио), при настроенных папках записей — только их, иначе всё аудио; уже " +
                "зарегистрированные записи пропускаются. Необязательный limit — сколько строк " +
                "просмотреть за прогон (по умолчанию 200, до 1000). Честно возвращает счётчики: " +
                "найдено, добавлено, пропущено. Файлы не копируются, в базу попадают только " +
                "метаданные. Телефонные звонки сюда не входят — их запись Android запрещает.",
            needsPermission = "android.permission.READ_MEDIA_AUDIO",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "string")
                        put("description", "Сколько аудиофайлов просмотреть за прогон, 1–1000 (по умолчанию 200).")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "recording_import",
            description = "Зарегистрировать конкретный аудиофайл как запись: path — абсолютный путь " +
                "к файлу или content:// URI (обязательно). transcribe=true (по умолчанию) сразу " +
                "запускает распознавание речи — модель должна быть уже скачана, иначе инструмент " +
                "честно сообщит об ошибке; transcribe=false только регистрирует запись в статусе " +
                "PENDING. language — код языка или auto (по умолчанию auto). content:// URI " +
                "копируется в хранилище приложения, чтобы расшифровка могла его прочитать. В " +
                "ответе — id записи и честный результат.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Абсолютный путь к аудиофайлу или content:// URI (обязательно).")
                    }
                    putJsonObject("transcribe") {
                        put("type", "boolean")
                        put("description", "Сразу расшифровать запись (по умолчанию true).")
                    }
                    putJsonObject("language") {
                        put("type", "string")
                        put("description", "Язык распознавания или auto (по умолчанию auto).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("path")) }
            },
        ),
        Tool(
            name = "messenger_voice_capture",
            description = "Записать звук, который сейчас играет другое приложение (голосовое " +
                "сообщение в мессенджере), и импортировать его как запись. duration_seconds — " +
                "сколько секунд писать (по умолчанию 30, допустимо 5–300; служба захвата может " +
                "ограничить длительность). transcribe=true (по умолчанию) сразу запускает " +
                "распознавание речи — модель должна быть уже скачана, иначе будет честная ошибка. " +
                "language — код языка или auto (по умолчанию auto). Нужны разрешение на запись " +
                "звука и РАЗОВОЕ согласие пользователя на захват системного звука (MediaProjection): " +
                "Android покажет диалог, и без согласия инструмент честно сообщит об отказе. Если " +
                "приложение-источник запретило захват, будет записана тишина — инструмент честно " +
                "об этом скажет. Файл сохраняется в хранилище приложения.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("duration_seconds") {
                        put("type", "string")
                        put("description", "Сколько секунд записывать, 5–300 (по умолчанию 30).")
                    }
                    putJsonObject("transcribe") {
                        put("type", "boolean")
                        put("description", "Сразу расшифровать запись (по умолчанию true).")
                    }
                    putJsonObject("language") {
                        put("type", "string")
                        put("description", "Язык распознавания или auto (по умолчанию auto).")
                    }
                }
                putJsonArray("required") { }
            },
        ),
        Tool(
            name = "commitments_extract",
            description = "Найти в расшифровках записей фразы-обязательства и договорённости " +
                "(«сделаю», «давай», «напомни», «I will»…) за период. window — окно: today или Nd " +
                "(по умолчанию 7d, максимум 365d). create=true — сохранить найденные кандидаты " +
                "как дела (todo) через существующий путь задач; создаются только явные совпадения " +
                "по ключевым фразам, ничего не выдумывается. Возвращает кандидатов с записью и " +
                "таймкодом и число созданных дел. Это эвристика по ключевым словам, а не гарантия — " +
                "проверяй кандидатов. Без create ничего не меняет.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("window") {
                        put("type", "string")
                        put("description", "Окно поиска: today или Nd, например 7d (по умолчанию 7d).")
                    }
                    putJsonObject("create") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Сохранить найденные кандидаты как дела (по умолчанию false)."
                        )
                    }
                }
                putJsonArray("required") { }
            },
        ),
    )
}
