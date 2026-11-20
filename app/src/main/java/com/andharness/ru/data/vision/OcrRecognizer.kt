package com.andharness.ru.data.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.andharness.ru.domain.vision.OcrEngine
import com.andharness.ru.domain.vision.OcrResult
import com.andharness.ru.domain.vision.OcrScript
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Single shared, offline OCR engine behind `ocr_image`, `pdf_ocr` and `screen_understand`.
 *
 * Why Tesseract and not ML Kit: ML Kit's text recognizer ships Latin/Chinese/Devanagari/Japanese/
 * Korean models only — there is no Cyrillic model, so Russian came out garbled. Tesseract4Android
 * runs fully on-device with the `rus+eng` / `eng` models bundled in `assets/tessdata`.
 *
 * Threading: `TessBaseAPI` is explicitly **not** thread-safe
 * (https://github.com/adaptech-cz/Tesseract4Android#usage), so the one shared instance is guarded
 * by a [Mutex] and every call runs on [Dispatchers.IO]. [close] releases the native instance.
 *
 * Honest degradation: if the native engine cannot start (missing/corrupt model, native load
 * failure), recognition falls back to ML Kit Latin and the returned [OcrResult] says so
 * ([OcrResult.engine] = [OcrEngine.ML_KIT] plus a human-readable [OcrResult.note]) instead of
 * silently pretending Cyrillic was recognized.
 *
 * References:
 * - Tesseract4Android usage / traineddata layout — https://github.com/adaptech-cz/Tesseract4Android
 * - `TessBaseAPI` (init/setImage/getUTF8Text/clear/recycle) —
 *   https://github.com/adaptech-cz/Tesseract4Android/blob/master/tesseract4android/src/main/java/com/googlecode/tesseract/android/TessBaseAPI.java
 * - AssetManager (list/open/openFd) — https://developer.android.com/reference/android/content/res/AssetManager
 * - ML Kit text recognition (Latin fallback) —
 *   https://developers.google.com/ml-kit/vision/text-recognition/v2/android
 */
@Singleton
class OcrRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mutex = Mutex()

    /** The one native instance; null until first use or after a failure/close. */
    private var tesseract: TessBaseAPI? = null

    /** Language string currently loaded into [tesseract] (`rus+eng` / `eng`). */
    private var loadedLanguages: String? = null

    /** Last init failure, surfaced in the honest note of a fallback result. */
    private var initFailure: String? = null

    /** Set once the bundled models have been extracted; avoids re-reading 8 MB of assets. */
    private var assetsReady = false

    /**
     * Recognizes [bitmap] with [OcrScript.CYRILLIC] (Tesseract `rus+eng`), which covers Cyrillic
     * and Latin text. The script is no longer user-configurable.
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult = recognize(bitmap, OcrScript.CYRILLIC)

    /** Recognizes [bitmap] with an explicit [script] (used by tests and explicit callers). */
    suspend fun recognize(bitmap: Bitmap, script: OcrScript): OcrResult =
        withContext(Dispatchers.IO) {
            // One lock covers init + recognition: `TessBaseAPI` is not thread-safe, and obtaining
            // the engine outside the lock could hand out an instance whose loaded language was
            // re-initialized for another script in between.
            mutex.withLock {
                val engine = obtainEngine(script)
                    ?: return@withLock mlKitLatin(bitmap, script, initFailure)
                try {
                    engine.setImage(bitmap)
                    val text = engine.getUTF8Text().orEmpty()
                    OcrResult(text.trim(), script, OcrEngine.TESSERACT)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A native failure leaves the instance in an unknown state: drop it and report
                    // the fallback honestly instead of returning a half-broken result.
                    val reason = e.message ?: e::class.java.simpleName
                    releaseEngine()
                    mlKitLatin(bitmap, script, reason)
                } finally {
                    // Frees the image, keeps the (still initialized) engine for the next call.
                    // Skipped when the failure path already recycled it.
                    if (tesseract === engine) runCatching { engine.clear() }
                }
            }
        }

    /** Releases the native engine. Safe to call more than once. */
    fun close() {
        releaseEngine()
    }

    /**
     * Returns an initialized engine for [script], re-initializing when the language changes
     * (`init` may be called repeatedly — see the Tesseract4Android docs). Returns null when the
     * native engine or the model files are unusable; [initFailure] then explains why.
     */
    private fun obtainEngine(script: OcrScript): TessBaseAPI? {
        val instance = tesseract ?: runCatching { TessBaseAPI() }
            .onFailure { initFailure = "нативная библиотека Tesseract недоступна (${it.message})" }
            .getOrNull()
            ?: return null
        tesseract = instance
        if (loadedLanguages == script.tesseractLanguages) return instance
        return try {
            val dataPath = ensureTrainedData()
            if (instance.init(dataPath, script.tesseractLanguages)) {
                // Mixed screenshots (menu labels + numbers) read better with automatic layout
                // analysis; PSM_AUTO is Tesseract's default and is set explicitly for clarity.
                instance.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                loadedLanguages = script.tesseractLanguages
                initFailure = null
                instance
            } else {
                initFailure = "Tesseract не принял модели «${script.tesseractLanguages}» " +
                    "из ${dataPath}tessdata"
                releaseEngine()
                null
            }
        } catch (e: Exception) {
            initFailure = "инициализация Tesseract не удалась: ${e.message}"
            releaseEngine()
            null
        }
    }

    private fun releaseEngine() {
        runCatching { tesseract?.recycle() }
        tesseract = null
        loadedLanguages = null
    }

    /**
     * Copies the bundled tessdata_fast models from `assets/tessdata` into the app's private
     * `files/tesseract/tessdata`, which is the only location Tesseract can read them from on
     * API 29+ (see the Tesseract4Android prerequisites). Idempotent: existing files with the same
     * uncompressed length are reused. Returns the parent directory of `tessdata`.
     */
    private fun ensureTrainedData(): String {
        val root = File(context.filesDir, TESSDATA_ROOT_DIR)
        val tessdata = File(root, TESSDATA_DIR)
        if (!tessdata.isDirectory && !tessdata.mkdirs()) {
            throw IOException("не удалось создать каталог ${tessdata.absolutePath}")
        }
        if (!assetsReady) {
            val names = context.assets.list(TESSDATA_DIR).orEmpty()
                .filter { it.endsWith(".traineddata") }
            if (names.isEmpty()) {
                throw IOException("во встроенных ресурсах нет моделей tessdata")
            }
            for (name in names) {
                val assetPath = "$TESSDATA_DIR/$name"
                val target = File(tessdata, name)
                val expectedSize = runCatching { context.assets.openFd(assetPath).use { it.length } }
                    .getOrNull()
                if (target.isFile && target.length() > 0L &&
                    (expectedSize == null || target.length() == expectedSize)
                ) {
                    continue
                }
                // Write to a temp file and rename, so a crash mid-copy cannot leave a truncated
                // model behind (Tesseract would fail to load it).
                val temp = File(tessdata, "$name.tmp")
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    throw IOException("не удалось записать модель $name")
                }
            }
            assetsReady = true
        }
        // `init` appends "tessdata" itself; the wrapper normalizes the trailing separator.
        return root.absolutePath
    }

    /**
     * Latin-only ML Kit fallback. Only used when Tesseract cannot run, and the returned note always
     * says so — [script] is preserved so callers can see which script was requested.
     */
    private suspend fun mlKitLatin(
        bitmap: Bitmap,
        script: OcrScript,
        reason: String?,
    ): OcrResult {
        val note = buildString {
            append("Движок Tesseract недоступен")
            if (!reason.isNullOrBlank()) append(" ($reason)")
            append("; использован ML Kit, который распознаёт только латиницу")
            if (script == OcrScript.CYRILLIC) append(", — кириллица может быть искажена")
            append('.')
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val text = try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine<Text?> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }?.text.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit text recognition failed (reason=${reason ?: "unknown"})", e)
            ""
        } finally {
            recognizer.close()
        }
        return OcrResult(text.trim(), script, OcrEngine.ML_KIT, note)
    }

    companion object {
        private const val TAG = "Andharness"

        /** Root under `filesDir`; must contain the `tessdata` subdirectory Tesseract expects. */
        const val TESSDATA_ROOT_DIR = "tesseract"
        const val TESSDATA_DIR = "tessdata"
    }
}
