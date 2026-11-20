package com.andharness.ru.ui.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryFormatTest {

    @Test
    fun importanceLabelsMatchTheStoredScale() {
        // NoteEntity: 0 = normal, 1 = important, 2 = critical; saveMemory coerces into 0..2.
        assertEquals("обычная", memoryImportanceLabel(0))
        assertEquals("важная", memoryImportanceLabel(1))
        assertEquals("критичная", memoryImportanceLabel(2))
    }

    @Test
    fun outOfRangeImportanceIsTreatedAsNormal() {
        // The data layer can never store these, so they must not invent a label.
        assertEquals("обычная", memoryImportanceLabel(-1))
        assertEquals("обычная", memoryImportanceLabel(3))
    }

    @Test
    fun metaTextJoinsIdImportanceAndTags() {
        assertEquals("#42 · важная · кофе, утро", memoryMetaText(42L, 1, "кофе, утро"))
    }

    @Test
    fun blankTagsAreOmittedWithoutADanglingSeparator() {
        assertEquals("#7 · обычная", memoryMetaText(7L, 0, ""))
        assertEquals("#7 · обычная", memoryMetaText(7L, 0, "   "))
    }
}
