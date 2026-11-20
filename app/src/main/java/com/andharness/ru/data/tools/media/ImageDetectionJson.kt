package com.andharness.ru.data.tools.media

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure (Android-free) shaping of `image_detect_objects` results into the tool's JSON payload.
 *
 * The handler maps ML Kit `DetectedObject`s to [Detection] (plain data, no Android/ML Kit types),
 * and this object sorts them by confidence (descending), caps the list and renders the JSON. Being
 * Android-free it is testable on the plain JVM.
 *
 * Reference: ML Kit object detection result shape (`DetectedObject.getBoundingBox()` /
 * `getLabels()` -> `Label.getText()` / `getConfidence()`) —
 * https://developers.google.com/ml-kit/vision/object-detection/android
 */
internal object ImageDetectionJson {

    /** Default cap on returned objects. */
    const val DEFAULT_MAX_RESULTS = 20

    /** Highest accepted cap (clamped by the handler's argument parsing too). */
    const val HARD_MAX_RESULTS = 100

    /** One detected object reduced to plain data. `label` may be null when unclassified. */
    data class Detection(
        val label: String?,
        val confidence: Float,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    /** Shaped payload: the JSON text plus the number of objects actually included. */
    data class Shaped(val json: String, val count: Int)

    /**
     * Sorts [detections] by confidence descending (stable, so ties keep input order), caps them at
     * [maxResults] and renders a JSON array.
     *
     * Each item is `{"label","confidence","box":{"x","y","w","h"}}`. A blank/absent label becomes
     * `"unknown"`. [maxResults] below 0 is treated as 0 (empty array).
     */
    fun shape(
        detections: List<Detection>,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): Shaped {
        val cap = maxResults.coerceAtLeast(0)
        val selected = detections.sortedByDescending { it.confidence }.take(cap)
        val json = buildJsonArray {
            selected.forEach { detection ->
                add(
                    buildJsonObject {
                        put(
                            "label",
                            detection.label?.takeIf { it.isNotBlank() } ?: "unknown",
                        )
                        put("confidence", JsonPrimitive(detection.confidence))
                        put(
                            "box",
                            buildJsonObject {
                                put("x", JsonPrimitive(detection.left))
                                put("y", JsonPrimitive(detection.top))
                                put("w", JsonPrimitive(detection.width))
                                put("h", JsonPrimitive(detection.height))
                            },
                        )
                    },
                )
            }
        }
        return Shaped(json.toString(), selected.size)
    }
}
