package com.andharness.ru.data.index

import com.andharness.ru.data.index.DeviceIndexDiff.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the incremental diff: given the already-indexed sources and the current device
 * sources, it must classify each source as added / updated / unchanged and drop the removed ones.
 */
class DeviceIndexDiffTest {

    private fun meta(lastModified: Long, sizeBytes: Long) =
        DeviceIndexSourceMeta(lastModified = lastModified, sizeBytes = sizeBytes)

    @Test
    fun emptyIndexMarksEveryCurrentSourceAsAdded() {
        val plan = DeviceIndexDiff.plan(
            known = emptyMap(),
            current = listOf(
                Candidate("content://a", lastModified = 10L, sizeBytes = 100L),
                Candidate("content://b", lastModified = 20L, sizeBytes = 200L),
            ),
        )

        assertEquals(listOf("content://a", "content://b"), plan.toAdd)
        assertTrue(plan.toUpdate.isEmpty())
        assertTrue(plan.toDelete.isEmpty())
        assertEquals(0, plan.unchanged)
        assertEquals(listOf("content://a", "content://b"), plan.toIndex)
        assertEquals("a full first scan should not be a no-op", false, plan.isNoOp)
    }

    @Test
    fun identicalMetadataIsUnchanged() {
        val known = mapOf(
            "content://a" to meta(10L, 100L),
            "content://b" to meta(20L, 200L),
        )
        val plan = DeviceIndexDiff.plan(
            known = known,
            current = listOf(
                Candidate("content://a", lastModified = 10L, sizeBytes = 100L),
                Candidate("content://b", lastModified = 20L, sizeBytes = 200L),
            ),
        )

        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
        assertTrue(plan.toDelete.isEmpty())
        assertEquals(2, plan.unchanged)
        assertTrue(plan.isNoOp)
    }

    @Test
    fun changedLastModifiedIsUpdated() {
        val plan = DeviceIndexDiff.plan(
            known = mapOf("content://a" to meta(10L, 100L)),
            current = listOf(Candidate("content://a", lastModified = 11L, sizeBytes = 100L)),
        )

        assertEquals(listOf("content://a"), plan.toUpdate)
        assertTrue(plan.toAdd.isEmpty())
        assertEquals(0, plan.unchanged)
    }

    @Test
    fun changedSizeIsUpdated() {
        val plan = DeviceIndexDiff.plan(
            known = mapOf("content://a" to meta(10L, 100L)),
            current = listOf(Candidate("content://a", lastModified = 10L, sizeBytes = 101L)),
        )

        assertEquals(listOf("content://a"), plan.toUpdate)
    }

    @Test
    fun sourceGoneFromDeviceIsDeleted() {
        val plan = DeviceIndexDiff.plan(
            known = mapOf(
                "content://a" to meta(10L, 100L),
                "content://gone" to meta(20L, 200L),
            ),
            current = listOf(Candidate("content://a", lastModified = 10L, sizeBytes = 100L)),
        )

        assertEquals(listOf("content://gone"), plan.toDelete)
        assertEquals(1, plan.unchanged)
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
        assertFalse(plan.isNoOp)
    }

    @Test
    fun blankSourceUriIsIgnored() {
        val plan = DeviceIndexDiff.plan(
            known = emptyMap(),
            current = listOf(
                Candidate("   ", lastModified = 1L, sizeBytes = 1L),
                Candidate("", lastModified = 1L, sizeBytes = 1L),
                Candidate("content://ok", lastModified = 1L, sizeBytes = 1L),
            ),
        )

        assertEquals(listOf("content://ok"), plan.toAdd)
        assertEquals(0, plan.unchanged)
    }

    @Test
    fun mixedAddUpdateDeleteAndUnchangedInOnePass() {
        val known = mapOf(
            "content://same" to meta(10L, 100L),
            "content://changed" to meta(20L, 200L),
            "content://removed" to meta(30L, 300L),
        )
        val plan = DeviceIndexDiff.plan(
            known = known,
            current = listOf(
                Candidate("content://same", lastModified = 10L, sizeBytes = 100L),
                Candidate("content://changed", lastModified = 25L, sizeBytes = 200L),
                Candidate("content://new", lastModified = 40L, sizeBytes = 400L),
            ),
        )

        assertEquals(listOf("content://new"), plan.toAdd)
        assertEquals(listOf("content://changed"), plan.toUpdate)
        assertEquals(listOf("content://removed"), plan.toDelete)
        assertEquals(1, plan.unchanged)
        assertEquals(listOf("content://new", "content://changed"), plan.toIndex)
        assertFalse(plan.isNoOp)
    }
}
