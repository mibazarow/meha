package com.andharness.ru.data.tools.files

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.print.PrintDecision
import com.andharness.ru.domain.print.PrintUiOutcome
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream

/** `print_file`: hands a file to the system print service through the chat Activity. */
internal class PrintFileToolHandler(
    private val context: Context,
    private val io: FileToolIo,
    private val uiBridge: UiInteractionBridge,
) : ToolHandler {
    override val name = "print_file"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val file = File(io.path(arguments, "path"))
            if (!file.isFile) {
                return ToolResult(false, "Не файл: «${file.absolutePath}».")
            }
            // An explicit mime wins; otherwise it is derived from the extension (falling
            // back to star-slash-star for unknown extensions) by the shared helper.
            val requestedMime = (arguments["mime"] as? JsonPrimitive)?.content?.trim()
                .takeUnless { it.isNullOrEmpty() }
            val mime = requestedMime ?: io.mimeTypeOf(file)

            // android.print.PrintManager is the system printing service. A null instance
            // means this device has no printing service at all — reported honestly instead
            // of pretending the job was queued. The service is only PROBED here; the actual
            // print() call must run from an Activity (see below), so this handler never
            // calls it on the application context.
            // https://developer.android.com/reference/android/print/PrintManager
            if (context.getSystemService(Context.PRINT_SERVICE) !is PrintManager) {
                return ToolResult(
                    false,
                    "Системная служба печати недоступна на этом устройстве."
                )
            }

            // PrintDocumentInfo.Builder takes the display name. The page count is unknown
            // here: the file is streamed verbatim and the chosen print service decides how
            // many pages it renders into.
            // setContentType accepts a platform constant (not an arbitrary MIME string), so
            // the resolved mime is mapped to the closest constant.
            // https://developer.android.com/reference/android/print/PrintDocumentInfo
            val contentType =
                if (mime.startsWith("image/")) PrintDocumentInfo.CONTENT_TYPE_PHOTO
                else PrintDocumentInfo.CONTENT_TYPE_DOCUMENT

            // android.print.PrintDocumentAdapter streams the file to the print service:
            // onLayout() reports WHAT will be printed (one document of unknown page count),
            // onWrite() copies the raw bytes into the service's destination descriptor.
            // https://developer.android.com/reference/android/print/PrintDocumentAdapter
            val adapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?,
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder(file.name)
                        .setContentType(contentType)
                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                        .build()
                    // changed=true: the caller is always handed the layout result.
                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback,
                ) {
                    try {
                        // Byte-for-byte copy through a fixed 8 KiB buffer, so an arbitrarily
                        // large file is never held in memory. The descriptor's
                        // FileOutputStream is closed once the copy is complete.
                        // java.io.FileOutputStream —
                        // https://developer.android.com/reference/java/io/FileOutputStream
                        FileOutputStream(destination.fileDescriptor).use { out ->
                            file.inputStream().use { ins ->
                                val buffer = ByteArray(COPY_BUFFER_SIZE)
                                while (true) {
                                    if (cancellationSignal?.isCanceled == true) {
                                        callback.onWriteCancelled()
                                        return
                                    }
                                    val read = ins.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                            out.flush()
                        }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }

            // print() may be called ONLY from an Activity: the platform checks
            // `mContext instanceof Activity` and otherwise throws
            // IllegalStateException("Can print only from an activity"). This handler only
            // has the application context, so the adapter is handed to the chat Activity
            // through the UI bridge, which calls print() with an Activity context (BUG-023).
            // The bounded wait returns an honest failure if no foreground Activity can
            // show the dialog, and it never waits longer than the broadcast ANR window.
            // https://developer.android.com/reference/android/print/PrintManager#print(java.lang.String,%20android.print.PrintDocumentAdapter,%20android.print.PrintAttributes)
            val bridgeResult = uiBridge.requestBoundedUi(
                UiRequest.PrintRequest(
                    jobName = "Меха: ${file.name}",
                    adapter = adapter,
                    attributes = PrintAttributes.Builder().build(),
                )
            )
            val (outcome, failureMessage) = when (bridgeResult) {
                UiResult.PrintDialogShown -> PrintUiOutcome.DIALOG_SHOWN to null
                is UiResult.Failed -> PrintUiOutcome.FAILED to bridgeResult.message
                UiResult.Cancelled -> PrintUiOutcome.CANCELLED to null
                else -> PrintUiOutcome.UNEXPECTED to null
            }
            val decision = PrintDecision.from(
                outcome = outcome,
                fileName = file.name,
                mime = mime,
                failureMessage = failureMessage,
            )
            // Success here means "dialog shown", never "printed" — the user still confirms.
            ToolResult(decision.success, decision.message)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private companion object {
        private const val COPY_BUFFER_SIZE = 8 * 1024
    }
}
