package com.andharness.ru.data.tools.camera

import com.andharness.ru.data.tools.camera.CameraSelection.Camera
import com.andharness.ru.data.tools.camera.CameraSelection.Lens
import com.andharness.ru.data.tools.camera.CameraSelection.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the lens → camera-id selection used by every Camera2 tool (no Android APIs).
 */
class CameraSelectionTest {

    private val backOnly = listOf(
        Camera(id = "0", lens = Lens.BACK, index = 0),
    )

    private val frontAndBack = listOf(
        Camera(id = "0", lens = Lens.BACK, index = 0),
        Camera(id = "1", lens = Lens.FRONT, index = 1),
    )

    private val multiBack = listOf(
        Camera(id = "0", lens = Lens.BACK, index = 0),
        Camera(id = "2", lens = Lens.BACK, index = 2),
        Camera(id = "3", lens = Lens.FRONT, index = 3),
        Camera(id = "9", lens = Lens.EXTERNAL, index = 9),
    )

    @Test
    fun `defaults to the back camera when neither lens nor id is given`() {
        val result = CameraSelection.resolve(lens = null, cameraId = null, cameras = frontAndBack)
        assertEquals(Result.Found("0", Lens.BACK), result)
    }

    @Test
    fun `blank arguments still select the back camera`() {
        val result = CameraSelection.resolve(lens = "  ", cameraId = "", cameras = frontAndBack)
        assertEquals(Result.Found("0", Lens.BACK), result)
    }

    @Test
    fun `front lens selects the front camera id`() {
        val result = CameraSelection.resolve(lens = "front", cameraId = null, cameras = frontAndBack)
        assertEquals(Result.Found("1", Lens.FRONT), result)
    }

    @Test
    fun `lens parsing is case-insensitive and accepts Russian synonyms`() {
        assertEquals(Lens.FRONT, CameraSelection.parseLens("FRONT"))
        assertEquals(Lens.FRONT, CameraSelection.parseLens("фронтальная"))
        assertEquals(Lens.BACK, CameraSelection.parseLens("Основная"))
        assertEquals(Lens.BACK, CameraSelection.parseLens("rear"))
        assertEquals(Lens.EXTERNAL, CameraSelection.parseLens("external"))
        assertEquals(null, CameraSelection.parseLens("sideways"))
        assertEquals(null, CameraSelection.parseLens(null))
        assertEquals(null, CameraSelection.parseLens(""))
    }

    @Test
    fun `explicit camera id wins over the lens argument`() {
        val result = CameraSelection.resolve(lens = "front", cameraId = "9", cameras = multiBack)
        assertEquals(Result.Found("9", Lens.EXTERNAL), result)
    }

    @Test
    fun `unknown camera id is rejected with the available ids`() {
        val result = CameraSelection.resolve(lens = null, cameraId = "42", cameras = multiBack)
        assertTrue(result is Result.Missing)
        val message = (result as Result.Missing).message
        assertTrue(message.contains("42"))
        assertTrue(message.contains("0"))
        assertTrue(message.contains("3"))
    }

    @Test
    fun `missing front camera is an honest error naming the available cameras`() {
        val result = CameraSelection.resolve(lens = "front", cameraId = null, cameras = backOnly)
        assertTrue(result is Result.Missing)
        val message = (result as Result.Missing).message
        assertTrue(message.contains("фронтальная"))
        assertTrue(message.contains("0"))
    }

    @Test
    fun `unrecognised lens token is rejected instead of falling back`() {
        val result = CameraSelection.resolve(lens = "wide", cameraId = null, cameras = frontAndBack)
        assertTrue(result is Result.Missing)
        assertTrue((result as Result.Missing).message.contains("front или back"))
    }

    @Test
    fun `several cameras with the same facing resolve deterministically by index`() {
        val shuffled = multiBack.reversed()
        val result = CameraSelection.resolve(lens = "back", cameraId = null, cameras = shuffled)
        assertEquals(Result.Found("0", Lens.BACK), result)
    }

    @Test
    fun `empty camera list is reported honestly`() {
        val result = CameraSelection.resolve(lens = null, cameraId = null, cameras = emptyList())
        assertTrue(result is Result.Missing)
        assertTrue((result as Result.Missing).message.contains("Камеры не найдены"))
    }
}
