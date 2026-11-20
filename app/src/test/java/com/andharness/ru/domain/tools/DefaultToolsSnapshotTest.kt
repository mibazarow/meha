package com.andharness.ru.domain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization ("golden master") test for the tool catalog (W0 of the refactoring plan).
 *
 * [DefaultTools.all] feeds `ToolRegistryImpl.definitions()`, and the resulting order is part of the
 * prompt the model sees, so both the SET and the ORDER of tool names are behaviour. The expected
 * names are frozen in `src/test/resources/tool_names.txt`, generated from the pre-refactor code:
 * any rename, reorder, add or remove in the catalog fails here instead of silently changing the
 * model's prompt.
 *
 * This test is intentionally brittle: it is the safety net for the `DefaultTools.kt` split (W1).
 */
class DefaultToolsSnapshotTest {

    private fun expectedNames(): List<String> {
        val stream = javaClass.classLoader?.getResourceAsStream("tool_names.txt")
            ?: error("tool_names.txt is missing from the test resources")
        return stream.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
    }

    @Test
    fun catalogSizeIsUnchanged() {
        assertEquals(
            "the number of tools in the catalog changed",
            EXPECTED_TOOL_COUNT,
            DefaultTools.all().size
        )
    }

    @Test
    fun catalogNamesAndOrderAreUnchanged() {
        val expected = expectedNames()
        val actual = DefaultTools.all().map { it.name }
        assertEquals("catalog size differs from the snapshot", expected.size, actual.size)
        // Index-by-index comparison so a failure names the first diverging tool.
        for (index in expected.indices) {
            assertEquals("tool #$index changed", expected[index], actual[index])
        }
        assertEquals("tool catalog (names + order) changed", expected, actual)
    }

    @Test
    fun catalogHasNoDuplicateNames() {
        val names = DefaultTools.all().map { it.name }
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate tool names in the catalog: $duplicates", duplicates.isEmpty())
    }

    private companion object {
        /** Frozen pre-refactor catalog size — see `app/src/test/resources/tool_names.txt`. */
        const val EXPECTED_TOOL_COUNT = 546
    }
}
