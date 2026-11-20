package com.andharness.ru.data.tools.camera

/** Camera id + lens chosen for one tool call. */
internal data class ResolvedCamera(val id: String, val lens: CameraSelection.Lens)
