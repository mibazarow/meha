package com.andharness.ru.data.tools.archive

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler

/**
 * Builds one [ToolHandler] per archive tool (files_tar_create / files_tar_extract,
 * files_7z_create / files_7z_extract, files_rar_extract), in their original registration order.
 * This class is a thin coordinator: each tool lives in its own `<Tool>Handler` class and the
 * shared path/stream plumbing is in [ArchiveSupport].
 */
class ArchiveToolsHandler(private val context: Context) {

    fun handlers(): List<ToolHandler> {
        val support = ArchiveSupport(context)
        return listOf(
            FilesTarCreateToolHandler(support),
            FilesTarExtractToolHandler(support),
            Files7zExtractToolHandler(support),
            Files7zCreateToolHandler(support),
            FilesRarExtractToolHandler(support),
        )
    }
}
