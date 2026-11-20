package com.andharness.ru.data.tools.files

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge

/**
 * Builds one [ToolHandler] per files_* tool. The base directory resolves via
 * [FileToolIo.root]: the real shared-storage root (/storage/emulated/0) when the
 * MANAGE_EXTERNAL_STORAGE special permission is granted, otherwise the app-private dir.
 * Path arguments are containment-checked against every root from [FileToolIo.roots]
 * (the primary root plus removable volumes on API 30+), and resolve to canonical absolute paths.
 *
 * java.io.File reference:
 * - https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/File.html
 *   (listFiles, mkdirs, delete, deleteRecursively, renameTo; readText/writeText/copyTo
 *   are Kotlin stdlib extensions over File).
 */
class FilesToolsHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) {

    /** The files_* tools in their original registration order. */
    fun handlers(): List<ToolHandler> {
        val io = FileToolIo(context)
        val backups = FileBackups()
        val launcher = FileOpenLauncher(context, io, FileOpenChoiceStore(context))
        return listOf(
            FilesListToolHandler(io),
            FilesReadToolHandler(io),
            FilesWriteToolHandler({ args, key -> io.path(args, key) }, backups),
            FilesCreateDirToolHandler(io),
            FilesDeleteToolHandler(io),
            FilesMoveToolHandler(io),
            FilesRenameToolHandler(io),
            FilesCopyToolHandler(io, backups),
            FilesStatToolHandler(io),
            FilesDirSizeToolHandler(io),
            FilesOpenToolHandler(io, launcher),
            FilesAppendToolHandler({ args, key -> io.path(args, key) }),
            FilesSearchToolHandler(io),
            FilesGrepToolHandler(io),
            FilesHashToolHandler(io),
            FilesDuplicatesToolHandler(io),
            FilesZipToolHandler(io),
            FilesUnzipToolHandler(io),
            FilesZipListToolHandler(io),
            FilesGzipToolHandler(io),
            FilesGunzipToolHandler(io),
            ShareFileToolHandler(context, io),
            ShareFilesToolHandler(context, io),
            PrintFileToolHandler(context, io, uiBridge),
            FilesSplitToolHandler(io),
            FilesJoinToolHandler(io),
            FilesMimeToolHandler(io),
            FilesCompareToolHandler(io),
            FilesHideToolHandler(context, io),
            FilesChmodToolHandler(io),
        )
    }
}
