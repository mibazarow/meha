package com.andharness.ru.data.tools.media

import kotlin.math.roundToInt

/**
 * Pure (Android-free) target-size math for `image_svg_render`: turns an optional requested
 * width/height plus the SVG's intrinsic document size into the concrete raster size to render.
 *
 * Kept separate from the handler so it is testable on the plain JVM (no Robolectric), just like
 * [ImageEditParsing]. The AndroidSVG render itself (`SVG.renderToPicture(int, int)`) stays in the
 * handler.
 *
 * Reference: AndroidSVG `SVG.getDocumentWidth()` / `getDocumentHeight()` /
 * `renderToPicture(int widthInPixels, int heightInPixels)` —
 * https://github.com/BigBadaboom/androidsvg
 */
internal object ImageExtrasMath {

    /** Hard cap on a single rendered SVG side, in pixels, to keep memory sane. */
    const val MAX_RENDER_SIDE = 4096

    /** Fallback side used when an SVG declares no intrinsic width/height (e.g. only a viewBox). */
    private const val FALLBACK_SIDE = 1024f

    /**
     * Resolves the render size to `[width, height]` pixels.
     *
     * - both [requestedWidth] and [requestedHeight] given -> taken verbatim;
     * - only one given -> the other is derived from the [intrinsicWidth]/[intrinsicHeight] ratio;
     * - neither given -> the intrinsic size (larger side first), or [FALLBACK_SIDE] when the SVG
     *   declares no usable size.
     *
     * Every returned side is clamped to `1..`[MAX_RENDER_SIDE]. [requestedWidth] and
     * [requestedHeight] must be positive when non-null (the handler rejects non-positive input
     * before calling this).
     */
    fun svgTargetSize(
        requestedWidth: Int?,
        requestedHeight: Int?,
        intrinsicWidth: Float,
        intrinsicHeight: Float,
    ): IntArray {
        require(requestedWidth == null || requestedWidth > 0) { "requestedWidth must be positive" }
        require(requestedHeight == null || requestedHeight > 0) { "requestedHeight must be positive" }

        val baseWidth = if (intrinsicWidth > 0f) intrinsicWidth else FALLBACK_SIDE
        val baseHeight = if (intrinsicHeight > 0f) intrinsicHeight else FALLBACK_SIDE
        val ratio = baseWidth / baseHeight

        val width: Int
        val height: Int
        when {
            requestedWidth != null && requestedHeight != null -> {
                width = requestedWidth
                height = requestedHeight
            }
            requestedWidth != null -> {
                width = requestedWidth
                height = (requestedWidth / ratio).roundToInt()
            }
            requestedHeight != null -> {
                height = requestedHeight
                width = (requestedHeight * ratio).roundToInt()
            }
            baseWidth >= baseHeight -> {
                width = baseWidth.roundToInt()
                height = (baseWidth / ratio).roundToInt()
            }
            else -> {
                height = baseHeight.roundToInt()
                width = (baseHeight * ratio).roundToInt()
            }
        }
        return intArrayOf(
            width.coerceIn(1, MAX_RENDER_SIDE),
            height.coerceIn(1, MAX_RENDER_SIDE),
        )
    }
}
