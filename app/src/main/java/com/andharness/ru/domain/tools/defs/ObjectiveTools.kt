package com.andharness.ru.domain.tools.defs

import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---- checklist §1: long-running objectives with a plan and checkpoints (goal → plan → steps →
// progress → resume after a restart) ----
internal object ObjectiveTools {
    fun defs(): List<Tool> = listOf(
        Tool(
            name = "objective_create",
            description = "Создать долгую цель (задачу с планом и чекпоинтами), которую можно " +
                "выполнять по шагам в нескольких ходах и продолжить после перезапуска приложения. " +
                "Цель сразу становится АКТИВНОЙ и привязывается к текущему чату, поэтому в каждом " +
                "следующем ходе агент видит её и продолжает. Шаги — список строк по порядку " +
                "(например, «собрать данные», «сделать отчёт», «отправить»). Если шаги не заданы, " +
                "цель можно вести без плана. Не создавай цель для одной быстрой операции — для этого " +
                "есть обычные инструменты.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "Короткое название цели (до 120 символов).")
                    }
                    putJsonObject("goal") {
                        put("type", "string")
                        put("description", "Что именно нужно получить в итоге (до 600 символов).")
                    }
                    putJsonObject("steps") {
                        put("type", "string")
                        put(
                            "description",
                            "Необязательный план: шаги по порядку, каждый с новой строки " +
                                "(можно также передать JSON-массив строк)."
                        )
                    }
                    putJsonObject("chat_id") {
                        put("type", "string")
                        put("description", "Необязательно: id чата для привязки. Обычно не нужно — по умолчанию берётся текущий чат.")
                    }
                }
                putJsonArray("required") {
                    add(kotlinx.serialization.json.JsonPrimitive("title"))
                    add(kotlinx.serialization.json.JsonPrimitive("goal"))
                }
            },
        ),
        Tool(
            name = "objective_list",
            description = "Показать сохранённые долгие цели: id, название, статус, прогресс " +
                "(сделано/всего шагов) и следующий шаг. Ничего не меняет. Можно отфильтровать по " +
                "статусу (active/paused/done/cancelled).",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("status") {
                        put("type", "string")
                        put("description", "Необязательный фильтр: active, paused, done или cancelled.")
                    }
                }
            },
        ),
        Tool(
            name = "objective_get",
            description = "Показать одну долгую цель подробно: цель, статус, прогресс, все шаги с " +
                "их статусами и заметками, привязку к чату и время работы. Ничего не меняет. " +
                "Цель можно указать по id или по названию.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "id цели или её название.")
                    }
                }
                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("id")) }
            },
        ),
        Tool(
            name = "objective_step_update",
            description = "Отметить статус шага цели во время работы — это чекпоинт. Указывай шаг по " +
                "id (например «s2») или по номеру (1, 2, …). Статусы: pending, in_progress, done, " +
                "blocked. Можно добавить короткую заметку о результате или причине блокировки. " +
                "Отмечай done только реально выполненное — не выдумывай.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("objective") {
                        put("type", "string")
                        put("description", "Цель: id или название (по умолчанию — активная).")
                    }
                    putJsonObject("step") {
                        put("type", "string")
                        put("description", "Шаг: id (например «s2») или номер по порядку (1, 2, …).")
                    }
                    putJsonObject("status") {
                        put("type", "string")
                        put("description", "Новый статус шага: pending, in_progress, done или blocked.")
                    }
                    putJsonObject("note") {
                        put("type", "string")
                        put("description", "Необязательная короткая заметка: что сделано или что мешает.")
                    }
                }
                putJsonArray("required") {
                    add(kotlinx.serialization.json.JsonPrimitive("step"))
                    add(kotlinx.serialization.json.JsonPrimitive("status"))
                }
            },
        ),
        Tool(
            name = "objective_next",
            description = "Показать следующий шаг активной цели и подсказку, что делать дальше. " +
                "Ничего не меняет. Если шагов нет или все выполнены — честно скажет об этом. " +
                "Вызови, когда продолжаешь долгую цель и нужно понять, на чём остановились.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("objective") {
                        put("type", "string")
                        put("description", "Цель: id или название (по умолчанию — активная).")
                    }
                }
            },
        ),
        Tool(
            name = "objective_finish",
            description = "Завершить долгую цель успешно (статус done). Вызывай, когда все шаги реально " +
                "выполнены. Если цель уже завершена или отменена — честно сообщит об этом и ничего не " +
                "изменит.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("objective") {
                        put("type", "string")
                        put("description", "Цель: id или название (по умолчанию — активная).")
                    }
                }
            },
        ),
        Tool(
            name = "objective_cancel",
            description = "Отменить долгую цель (статус cancelled), если продолжать её не нужно или " +
                "пользователь передумал. Отменённую цель нельзя возобновить — при необходимости " +
                "создай новую. Если цель уже завершена или отменена — честно сообщит об этом.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("objective") {
                        put("type", "string")
                        put("description", "Цель: id или название (по умолчанию — активная).")
                    }
                }
            },
        ),
        Tool(
            name = "objective_resume",
            description = "Возобновить отложенную (пауза) долгую цель — снова сделать её активной, чтобы " +
                "продолжить в этом чате. Приостановленные цели НЕ выполняются сами по себе; их нужно " +
                "явно возобновить, когда пользователь просит продолжить. Завершённую или отменённую " +
                "цель возобновить нельзя.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("objective") {
                        put("type", "string")
                        put("description", "Необязательно: id или название цели. По умолчанию — последняя приостановленная цель текущего чата.")
                    }
                }
            },
        ),
        Tool(
            name = "objective_pause",
            description = "Поставить долгую цель на паузу (статус paused), если сейчас её продолжать " +
                "нельзя или не нужно (например, ждём пользователя или внешнее событие). На паузе цель " +
                "НЕ подсказывается агенту и не выполняется сама — продолжит только после явного " +
                "objective_resume. Прогресс и заметки шагов сохраняются.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("objective") {
                        put("type", "string")
                        put("description", "Цель: id или название (по умолчанию — активная).")
                    }
                }
            },
        ),
        Tool(
            name = "objective_run",
            description = "Запустить долгую цель в ФОНЕ: агент продолжит её сам, по следующему шагу, " +
                "и запишет прогресс. Работает, пока приложение в фоне; несколько целей могут " +
                "выполняться параллельно, но не больше заданного лимита (остальные встают в " +
                "очередь) — статус смотри через objective_workers_status. Не запускает цель, если " +
                "она завершена, на паузе, заблокирована или исчерпала бюджет фоновых шагов, а также " +
                "если не настроена LLM — в этом случае честно сообщит об этом. Без аргумента " +
                "запускает все подходящие активные цели.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("objective") {
                        put("type", "string")
                        put("description", "Необязательно: id или название цели. Без него запускаются все подходящие активные цели.")
                    }
                }
            },
        ),
        Tool(
            name = "objective_stop",
            description = "Остановить фоновое выполнение долгой цели (или всех сразу, если цель не " +
                "указана). Уже выполненные шаги и заметки сохраняются; сама цель не отменяется — её " +
                "можно снова запустить через objective_run.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("objective") {
                        put("type", "string")
                        put("description", "Необязательно: id или название цели. Без него останавливаются все выполняющиеся и ожидающие цели.")
                    }
                }
            },
        ),
        Tool(
            name = "objective_workers_status",
            description = "Показать, что сейчас выполняет фоновый исполнитель целей: какие цели " +
                "выполняются (running), какие ждут в очереди (queued), текущий лимит параллельности, " +
                "эффективный лимит (может быть снижен охраной питания) и время последнего запуска. " +
                "Ничего не меняет.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        ),
        Tool(
            name = "objective_concurrency_set",
            description = "Задать максимальное число долгих целей, выполняемых одновременно в фоне " +
                "(лимит параллельности). Допустимо от 1 до 3; по умолчанию 2. Значение сохраняется. " +
                "При слабом заряде или нагреве охрана питания всё равно снижает параллельность до 1.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("max") {
                        put("type", "string")
                        put("description", "Максимум одновременно выполняемых целей: 1, 2 или 3.")
                    }
                }
                putJsonArray("required") {
                    add(kotlinx.serialization.json.JsonPrimitive("max"))
                }
            },
        ),
    )
}
