package com.andharness.ru.ui.memory

/**
 * Presentation-only helpers for the «Что агент помнит» screen. Kept free of Compose/Android types
 * so they are unit-testable on the JVM.
 *
 * `NoteEntity.importance` is documented as `0 = normal, 1 = important, 2 = critical`
 * (`data/db/entity/NoteEntity.kt`) and writes are coerced into `0..2`
 * (`AssistRepositoryImpl.saveMemory`). Anything outside that range is therefore treated as normal
 * rather than inventing a label for a value the data layer can never store.
 */
internal fun memoryImportanceLabel(importance: Int): String = when (importance) {
    1 -> "важная"
    2 -> "критичная"
    else -> "обычная"
}

/**
 * Compact one-line metadata for a memory row: the persisted id, the human importance and, when
 * present, the lexical [tags] the agent attached for recall. Tags are omitted entirely (including
 * their separator) when blank, so a tagless memory does not end with a dangling «·».
 */
internal fun memoryMetaText(id: Long, importance: Int, tags: String): String {
    val base = "#$id · ${memoryImportanceLabel(importance)}"
    return if (tags.isBlank()) base else "$base · $tags"
}
