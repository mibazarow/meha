package com.andharness.ru.data.tools.camera

import com.andharness.ru.data.tools.camera.CameraSelection.Camera
import com.andharness.ru.data.tools.camera.CameraSelection.Lens
import com.andharness.ru.data.tools.camera.CameraSelection.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the `lens` mapping used by `camera_capture` (back/front), matching GOAL A:
 * `back` is the default, `front` picks the front camera and an explicit `camera_id` wins.
 */
class CameraCaptureLensTest {

    private val cameras = listOf(
        Camera(id = "0", lens = Lens.BACK, index = 0),
        Camera(id = "1", lens = Lens.FRONT, index = 1),
    )

    @Test
    fun `default lens is back`() {
        assertEquals("back", CameraSelection.DEFAULT_LENS)
        assertEquals(Lens.BACK, CameraSelection.parseLens(CameraSelection.DEFAULT_LENS))
        assertEquals(Result.Found("0", Lens.BACK), CameraSelection.resolve(null, null, cameras))
    }

    @Test
    fun `front lens maps to the front camera`() {
        assertEquals(Lens.FRONT, CameraSelection.parseLens("front"))
        assertEquals(Result.Found("1", Lens.FRONT), CameraSelection.resolve("front", null, cameras))
    }

    @Test
    fun `back lens maps to the back camera`() {
        assertEquals(Result.Found("0", Lens.BACK), CameraSelection.resolve("back", null, cameras))
    }

    @Test
    fun `camera_id wins over lens`() {
        assertEquals(Result.Found("1", Lens.FRONT), CameraSelection.resolve("back", "1", cameras))
    }

    @Test
    fun `unknown lens is reported honestly instead of falling back`() {
        val result = CameraSelection.resolve("sideways", null, cameras)
        assertTrue(result is Result.Missing)
    }
}
