package com.andharness.ru.data.tools.web

import android.content.Context
import java.io.File

/**
 * Resolves the on-disk file for a named cookie session under `filesDir/web_sessions`. The name is
 * sanitised so a tool argument can never escape the directory.
 */
internal class WebSessionStore(private val appContext: Context) {

    fun file(name: String): File {
        val dir = File(appContext.filesDir, "web_sessions").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "default" }
        return File(dir, "$safe.json")
    }
}
