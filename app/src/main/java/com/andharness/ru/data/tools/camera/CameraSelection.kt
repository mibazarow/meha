package com.andharness.ru.data.tools.camera

/**
 * Pure (no Android imports, JVM-unit-testable) lens → camera-id selection shared by every
 * Camera2 tool (`camera_capture`, `camera_burst`, `camera_video_record`, `camera_zoom`,
 * `camera_manual`).
 *
 * The Camera2 layer converts every entry of `CameraManager.getCameraIdList()` plus its
 * `CameraCharacteristics.LENS_FACING` into a [Camera] and hands the list here, so the tools never
 * guess an id themselves and report an honest error when the requested lens is absent.
 *
 * References used to design the selection semantics:
 * - `CameraManager.getCameraIdList()` —
 *   https://developer.android.com/reference/android/hardware/camera2/CameraManager#getCameraIdList()
 * - `CameraCharacteristics.LENS_FACING` (`LENS_FACING_FRONT` / `LENS_FACING_BACK` /
 *   `LENS_FACING_EXTERNAL`) —
 *   https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_FACING
 */
object CameraSelection {

    /** Which physical lens the caller asked for. */
    enum class Lens { FRONT, BACK, EXTERNAL, UNKNOWN }

    /**
     * One camera as reported by the framework: its [id], its [lens] and [index] — the position in
     * `cameraIdList()`. The index keeps the choice deterministic when a facing has several cameras
     * (e.g. several back lenses on multi-camera phones).
     */
    data class Camera(val id: String, val lens: Lens, val index: Int)

    /** Outcome of [resolve]: a concrete camera id, or an honest, user-facing failure message. */
    sealed interface Result {
        data class Found(val id: String, val lens: Lens) : Result
        data class Missing(val message: String) : Result
    }

    /** Lens applied when the caller passes neither `lens` nor `camera_id`. */
    const val DEFAULT_LENS = "back"

    /**
     * Maps a raw [raw] token to a [Lens]. Accepts English and Russian synonyms, case-insensitively.
     * Returns `null` for blank or unrecognised tokens so the caller can reject them instead of
     * silently falling back to another camera.
     */
    fun parseLens(raw: String?): Lens? {
        val token = raw?.trim()?.lowercase().orEmpty()
        if (token.isEmpty()) return null
        return when (token) {
            "front", "frontal", "selfie", "передняя", "фронтальная", "селфи" -> Lens.FRONT
            "back", "rear", "main", "задняя", "основная", "тыльная" -> Lens.BACK
            "external", "внешняя", "usb" -> Lens.EXTERNAL
            else -> null
        }
    }

    /**
     * Resolves the camera to use:
     * 1. an explicit [cameraId] always wins and must exist in [cameras];
     * 2. otherwise [lens] (default [DEFAULT_LENS]) must name a facing present on the device;
     * 3. an empty camera list, an unknown id, an unrecognised lens or a missing facing all produce
     *    [Result.Missing] with a Russian explanation — never a silent fallback to another camera.
     */
    fun resolve(lens: String?, cameraId: String?, cameras: List<Camera>): Result {
        if (cameras.isEmpty()) return Result.Missing("Камеры не найдены на устройстве.")

        val explicit = cameraId?.trim().orEmpty()
        if (explicit.isNotEmpty()) {
            val match = cameras.firstOrNull { it.id == explicit }
                ?: return Result.Missing(
                    "Камера с id «$explicit» не найдена. Доступные id: " +
                        cameras.joinToString(", ") { it.id } + ".",
                )
            return Result.Found(match.id, match.lens)
        }

        val token = lens?.trim().orEmpty()
        val requested = if (token.isEmpty()) {
            Lens.BACK
        } else {
            parseLens(token)
                ?: return Result.Missing("lens должен быть front или back (получено «$token»).")
        }
        if (requested == Lens.UNKNOWN) {
            return Result.Missing("lens должен быть front или back (получено «$token»).")
        }

        val candidates = cameras.filter { it.lens == requested }
        if (candidates.isEmpty()) {
            val available = cameras.joinToString(", ") { "${it.id} (${label(it.lens)})" }
            return Result.Missing("Не найдена ${label(requested)} камера. Доступны: $available.")
        }
        val chosen = candidates.minByOrNull { it.index } ?: candidates.first()
        return Result.Found(chosen.id, chosen.lens)
    }

    /** Russian human-readable label for a [lens]. */
    fun label(lens: Lens): String = when (lens) {
        Lens.FRONT -> "фронтальная"
        Lens.BACK -> "основная"
        Lens.EXTERNAL -> "внешняя"
        Lens.UNKNOWN -> "неизвестная"
    }
}
