package com.andharness.ru.data.tools.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure (Android-free) parsing and layout math shared by the new image-editing tools:
 * `image_crop`, `image_rotate`, `image_adjust`, `image_watermark`, `image_overlay_text`,
 * `image_collage` and `screen_annotate`.
 *
 * Everything here is testable on the plain JVM (no Robolectric): argument coercion, crop-rect
 * normalization, watermark placement, collage grid layout and the `screen_annotate` shape JSON
 * parser. The Android `Bitmap`/`Canvas` work stays in the handlers.
 *
 * Colors are parsed to packed ARGB `Int`s (`0xAARRGGBB`) without touching `android.graphics.Color`,
 * so the parser has no Android dependency. Reference: `android.graphics.Color` uses the same
 * `AARRGGBB` packing —
 * https://developer.android.com/reference/android/graphics/Color#argb(int,%20int,%20int,%20int)
 */
internal object ImageEditParsing {

    private val json = Json { ignoreUnknownKeys = true }

    /** Colour aliases accepted alongside `#RRGGBB` / `#AARRGGBB` / `#RGB`. */
    private val NAMED_COLORS = mapOf(
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF00FF00.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
        "transparent" to 0x00000000,
    )

    // ---- scalar argument coercion -------------------------------------------------------------

    /** Trimmed non-blank content of `args[key]`, or `null` when absent/blank. */
    fun optString(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Integer `args[key]`, or `null` when absent. Throws [IllegalArgumentException] when it is not
     * a number or is outside [min]..[max].
     */
    fun optInt(args: JsonObject, key: String, min: Int? = null, max: Int? = null): Int? {
        val raw = optString(args, key) ?: return null
        val value = raw.toIntOrNull()
            ?: throw IllegalArgumentException("Некорректное число «$key»: «$raw».")
        if (min != null && value < min) throw IllegalArgumentException("«$key» должно быть не меньше $min.")
        if (max != null && value > max) throw IllegalArgumentException("«$key» должно быть не больше $max.")
        return value
    }

    /** Double `args[key]` coerced into [min]..[max], or [default] when absent/blank. */
    fun optDoubleClamped(
        args: JsonObject,
        key: String,
        min: Double,
        max: Double,
        default: Double,
    ): Double {
        val raw = optString(args, key) ?: return default
        val value = raw.toDoubleOrNull()
            ?: throw IllegalArgumentException("Некорректное число «$key»: «$raw».")
        return value.coerceIn(min, max)
    }

    /**
     * Boolean `args[key]`: accepts `true`/`false` (case-insensitive) and `1`/`0`, returns [default]
     * when absent.
     */
    fun optBool(args: JsonObject, key: String, default: Boolean = false): Boolean {
        val raw = optString(args, key)?.lowercase() ?: return default
        return when (raw) {
            "true", "1", "yes", "да" -> true
            "false", "0", "no", "нет" -> false
            else -> throw IllegalArgumentException("Некорректное булево «$key»: «$raw».")
        }
    }

    /** Splits a comma/semicolon separated path list, trimming blanks. */
    fun splitPaths(raw: String?): List<String> =
        raw?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    // ---- colours ------------------------------------------------------------------------------

    /**
     * Parses `#RRGGBB`, `#AARRGGBB`, `#RGB` or a small set of colour names into packed ARGB.
     * Returns [default] when [raw] is blank; throws [IllegalArgumentException] for an
     * unrecognized value.
     */
    fun parseColor(raw: String?, default: Int = 0xFF000000.toInt()): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return default
        NAMED_COLORS[text.lowercase()]?.let { return it }
        val hex = text.removePrefix("#")
        val expanded = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> throw IllegalArgumentException(
                "Некорректный цвет «$raw» (ожидается #RRGGBB или #AARRGGBB).",
            )
        }
        val value = expanded.toLongOrNull(16)
            ?: throw IllegalArgumentException("Некорректный цвет «$raw».")
        return value.toInt()
    }

    // ---- crop ---------------------------------------------------------------------------------

    /**
     * Resolves the crop rectangle to `[x, y, width, height]` pixels, clamped to the source size.
     *
     * Either `box` (`x1,y1,x2,y2` as fractions 0..1 of the source) or `width`/`height` (with
     * optional `x`/`y`). Throws [IllegalArgumentException] for a bad value or an empty rectangle.
     */
    fun cropRect(args: JsonObject, srcWidth: Int, srcHeight: Int): IntArray {
        val box = optString(args, "box")
        if (box != null) {
            val parts = box.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 4) {
                throw IllegalArgumentException("box должен содержать 4 числа: x1,y1,x2,y2 (доли 0..1).")
            }
            val values = parts.map {
                it.toDoubleOrNull() ?: throw IllegalArgumentException("Некорректное box: «$box».")
            }
            if (values.any { it < 0.0 || it > 1.0 }) {
                throw IllegalArgumentException("Значения box должны быть в диапазоне 0..1.")
            }
            return clampRect(
                values[0] * srcWidth,
                values[1] * srcHeight,
                values[2] * srcWidth,
                values[3] * srcHeight,
                srcWidth,
                srcHeight,
            )
        }

        val x = optInt(args, "x", min = 0) ?: 0
        val y = optInt(args, "y", min = 0) ?: 0
        val width = optInt(args, "width", min = 1)
            ?: throw IllegalArgumentException("Укажи width (и height) либо box.")
        val height = optInt(args, "height", min = 1)
            ?: throw IllegalArgumentException("Укажи height (и width) либо box.")
        return clampRect(
            x.toDouble(),
            y.toDouble(),
            (x + width).toDouble(),
            (y + height).toDouble(),
            srcWidth,
            srcHeight,
        )
    }

    private fun clampRect(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        srcWidth: Int,
        srcHeight: Int,
    ): IntArray {
        val left = x1.coerceIn(0.0, srcWidth.toDouble()).roundToInt()
        val top = y1.coerceIn(0.0, srcHeight.toDouble()).roundToInt()
        val right = x2.coerceIn(0.0, srcWidth.toDouble()).roundToInt()
        val bottom = y2.coerceIn(0.0, srcHeight.toDouble()).roundToInt()
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Область обрезки пуста (проверь x, y, width, height или box).")
        }
        return intArrayOf(left, top, width, height)
    }

    // ---- rotation -----------------------------------------------------------------------------

    /** `true` when [flip] requests a horizontal mirror. Accepts `h`, `horizontal`, `both`. */
    fun flipHorizontal(flip: String?): Boolean =
        flip?.trim()?.lowercase() in setOf("h", "horizontal", "both", "hv", "vh")

    /** `true` when [flip] requests a vertical mirror. Accepts `v`, `vertical`, `both`. */
    fun flipVertical(flip: String?): Boolean =
        flip?.trim()?.lowercase() in setOf("v", "vertical", "both", "hv", "vh")

    /** Validates a `flip` value, returning the normalized token or `null` when absent. */
    fun parseFlip(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty()) return null
        return when (value) {
            "none", "no", "off" -> "none"
            "h", "horizontal", "v", "vertical", "both", "hv", "vh" -> value
            else -> throw IllegalArgumentException(
                "Некорректный flip «$raw» (допустимо: none, h, v, both).",
            )
        }
    }

    // ---- watermark / text placement -----------------------------------------------------------

    /**
     * Top-left corner `[x, y]` for a [wmWidth] x [wmHeight] watermark of size [wmWidth]/[wmHeight]
     * on a [canvasWidth] x [canvasHeight] canvas, inset by [margin] px.
     *
     * position: `top-left`, `top-right`, `bottom-left`, `bottom-right` (default) or `center`.
     */
    fun watermarkOrigin(
        position: String?,
        canvasWidth: Int,
        canvasHeight: Int,
        wmWidth: Int,
        wmHeight: Int,
        margin: Int,
    ): IntArray {
        val m = margin.coerceAtLeast(0)
        return when (position?.trim()?.lowercase()?.replace("_", "-") ?: "bottom-right") {
            "top-left", "topleft" -> intArrayOf(m, m)
            "top-right", "topright" -> intArrayOf(canvasWidth - wmWidth - m, m)
            "bottom-left", "bottomleft" -> intArrayOf(m, canvasHeight - wmHeight - m)
            "center", "centre" -> intArrayOf(
                (canvasWidth - wmWidth) / 2,
                (canvasHeight - wmHeight) / 2,
            )
            "bottom-right", "bottomright" -> intArrayOf(
                canvasWidth - wmWidth - m,
                canvasHeight - wmHeight - m,
            )
            else -> throw IllegalArgumentException(
                "Некорректная position «$position» (top-left, top-right, bottom-left, bottom-right, center).",
            )
        }
    }

    /**
     * Top-left corners for a tiled watermark: every `[stepX, stepY]` grid cell inside a
     * [canvasWidth] x [canvasHeight] canvas, starting [inset] px from the top-left. Steps are
     * coerced to at least 1 px so the loop always terminates.
     */
    fun tileOrigins(
        canvasWidth: Int,
        canvasHeight: Int,
        stepX: Int,
        stepY: Int,
        inset: Int = 0,
    ): List<IntArray> {
        val dx = stepX.coerceAtLeast(1)
        val dy = stepY.coerceAtLeast(1)
        val origins = ArrayList<IntArray>()
        var y = inset
        while (y < canvasHeight) {
            var x = inset
            while (x < canvasWidth) {
                origins.add(intArrayOf(x, y))
                x += dx
            }
            y += dy
        }
        return origins
    }

    /** Alignment token for `image_overlay_text`: `left` (default), `center` or `right`. */
    fun parseAlign(raw: String?): String = when (raw?.trim()?.lowercase()) {
        null, "", "left" -> "left"
        "center", "centre" -> "center"
        "right" -> "right"
        else -> throw IllegalArgumentException("Некорректное align «$raw» (left, center, right).")
    }

    // ---- collage grid -------------------------------------------------------------------------

    /**
     * Resolves the collage grid to `[rows, cols]`. Both given are taken verbatim (and must hold
     * [count]); a single given side derives the other; neither auto-picks a near-square grid.
     */
    fun grid(count: Int, rows: Int?, cols: Int?): IntArray {
        require(count > 0) { "count must be positive" }
        if (rows != null && cols != null) {
            if (rows * cols < count) {
                throw IllegalArgumentException(
                    "Сетка ${rows}x$cols вмещает ${rows * cols} ячеек, а изображений $count.",
                )
            }
            return intArrayOf(rows, cols)
        }
        if (cols != null) {
            return intArrayOf(ceil(count / cols.toDouble()).toInt(), cols)
        }
        if (rows != null) {
            return intArrayOf(rows, ceil(count / rows.toDouble()).toInt())
        }
        val columns = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        val derivedRows = ceil(count / columns.toDouble()).toInt()
        return intArrayOf(derivedRows, columns)
    }

    /**
     * Top-left corner `[x, y]` of cell [index] in a grid of [cols] columns, with cell size
     * [cellWidth] x [cellHeight] and [spacing] px around/between cells.
     */
    fun collageCell(index: Int, cols: Int, cellWidth: Int, cellHeight: Int, spacing: Int): IntArray {
        require(cols > 0) { "cols must be positive" }
        val row = index / cols
        val col = index % cols
        return intArrayOf(
            spacing + col * (cellWidth + spacing),
            spacing + row * (cellHeight + spacing),
        )
    }

    // ---- screen_annotate shape list -----------------------------------------------------------

    /** Parses the `shapes` JSON array of [AnnotationShape]s; throws [IllegalArgumentException]. */
    fun parseShapes(raw: String): List<AnnotationShape> {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("Некорректный JSON shapes: ${e.message}")
        }
        val array = element as? JsonArray
            ?: throw IllegalArgumentException("shapes должен быть JSON-массивом фигур.")
        if (array.isEmpty()) throw IllegalArgumentException("shapes пуст — нечего рисовать.")
        return array.mapIndexed { index, node ->
            val obj = node as? JsonObject
                ?: throw IllegalArgumentException("Фигура #$index должна быть JSON-объектом.")
            parseShape(obj, index)
        }
    }

    private fun parseShape(obj: JsonObject, index: Int): AnnotationShape {
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: throw IllegalArgumentException("У фигуры #$index не указан type.")
        return when (type) {
            "rect", "rectangle" -> RectShape(
                x = number(obj, "x", index) ?: 0f,
                y = number(obj, "y", index) ?: 0f,
                width = number(obj, "w", index) ?: number(obj, "width", index)
                    ?: throw IllegalArgumentException("У rect #$index нет w/width."),
                height = number(obj, "h", index) ?: number(obj, "height", index)
                    ?: throw IllegalArgumentException("У rect #$index нет h/height."),
                color = color(obj, "color", 0xFFFF3B30.toInt()),
                strokeWidth = number(obj, "stroke") ?: number(obj, "strokeWidth") ?: 4f,
                filled = bool(obj, "filled"),
            )
            "ellipse", "oval" -> EllipseShape(
                x = number(obj, "x", index) ?: 0f,
                y = number(obj, "y", index) ?: 0f,
                width = number(obj, "w", index) ?: number(obj, "width", index)
                    ?: throw IllegalArgumentException("У ellipse #$index нет w/width."),
                height = number(obj, "h", index) ?: number(obj, "height", index)
                    ?: throw IllegalArgumentException("У ellipse #$index нет h/height."),
                color = color(obj, "color", 0xFFFF3B30.toInt()),
                strokeWidth = number(obj, "stroke") ?: number(obj, "strokeWidth") ?: 4f,
                filled = bool(obj, "filled"),
            )
            "arrow" -> ArrowShape(
                x1 = number(obj, "x1", index) ?: throw IllegalArgumentException("У arrow #$index нет x1."),
                y1 = number(obj, "y1", index) ?: throw IllegalArgumentException("У arrow #$index нет y1."),
                x2 = number(obj, "x2", index) ?: throw IllegalArgumentException("У arrow #$index нет x2."),
                y2 = number(obj, "y2", index) ?: throw IllegalArgumentException("У arrow #$index нет y2."),
                color = color(obj, "color", 0xFFFF3B30.toInt()),
                strokeWidth = number(obj, "stroke") ?: number(obj, "strokeWidth") ?: 4f,
            )
            "text" -> TextShape(
                x = number(obj, "x", index) ?: 0f,
                y = number(obj, "y", index) ?: 0f,
                text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    .ifEmpty { throw IllegalArgumentException("У text #$index пустой text.") },
                size = number(obj, "size") ?: 32f,
                color = color(obj, "color", 0xFFFFFFFF.toInt()),
            )
            "highlight" -> HighlightShape(
                x = number(obj, "x", index) ?: 0f,
                y = number(obj, "y", index) ?: 0f,
                width = number(obj, "w", index) ?: number(obj, "width", index)
                    ?: throw IllegalArgumentException("У highlight #$index нет w/width."),
                height = number(obj, "h", index) ?: number(obj, "height", index)
                    ?: throw IllegalArgumentException("У highlight #$index нет h/height."),
                color = color(obj, "color", 0x80FFEB3B.toInt()),
            )
            else -> throw IllegalArgumentException(
                "Неизвестный type фигуры #$index: «$type» (rect, ellipse, arrow, text, highlight).",
            )
        }
    }

    private fun number(obj: JsonObject, key: String, index: Int = -1): Float? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw.toFloatOrNull()
            ?: throw IllegalArgumentException(
                if (index >= 0) "Некорректное $key у фигуры #$index: «$raw»." else "Некорректное $key: «$raw».",
            )
    }

    private fun bool(obj: JsonObject, key: String): Boolean {
        val raw = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return false
        return raw == "true" || raw == "1"
    }

    private fun color(obj: JsonObject, key: String, default: Int): Int {
        val raw = (obj[key] as? JsonPrimitive)?.contentOrNull
        return parseColor(raw, default)
    }
}

/** A single drawable annotation for `screen_annotate`, parsed from the `shapes` JSON array. */
internal sealed interface AnnotationShape

/** A rectangle outline (or filled box) at `(x, y)` with size `width` x `height` (px). */
internal data class RectShape(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Int,
    val strokeWidth: Float,
    val filled: Boolean,
) : AnnotationShape

/** An ellipse inscribed in the `(x, y, width, height)` box. */
internal data class EllipseShape(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Int,
    val strokeWidth: Float,
    val filled: Boolean,
) : AnnotationShape

/** A straight arrow from `(x1, y1)` to `(x2, y2)` with a head at the second point. */
internal data class ArrowShape(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val color: Int,
    val strokeWidth: Float,
) : AnnotationShape

/** Text drawn with its baseline-left at `(x, y)` in [size] px. */
internal data class TextShape(
    val x: Float,
    val y: Float,
    val text: String,
    val size: Float,
    val color: Int,
) : AnnotationShape

/** A translucent filled rectangle, typically used to highlight a region. */
internal data class HighlightShape(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Int,
) : AnnotationShape
