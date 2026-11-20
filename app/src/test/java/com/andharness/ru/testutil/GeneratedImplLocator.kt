package com.andharness.ru.testutil

import java.io.File

/**
 * Shared locator for the kapt-generated Room `AppDatabase_Impl.java`.
 *
 * Migration SQL tests compare hand-written migration SQL against the schema Room itself generates,
 * so they all need to find that generated file on disk. Its path depends on the build variant
 * (`sideloadDebug`, `storeDebug`, legacy `debug`, …) and on whether Gradle's working directory is
 * the module or the repo root. Because a stale variant can linger from an older build, the locator
 * prefers generated files whose embedded `RoomOpenHelper.Delegate(<version>)` matches the current
 * `AppDatabase.kt` version, then falls back to the newest generated file.
 */
object GeneratedImplLocator {

    private const val GENERATED_IMPL_RELATIVE =
        "com/andharness/ru/data/db/AppDatabase_Impl.java"

    private const val KAPT_ROOT_RELATIVE = "build/generated/source/kapt"

    private val KAPT_ROOT_CANDIDATES = listOf(
        KAPT_ROOT_RELATIVE,
        "app/$KAPT_ROOT_RELATIVE",
    )

    private val APP_DATABASE_CANDIDATES = listOf(
        "app/src/main/java/com/andharness/ru/data/db/AppDatabase.kt",
        "src/main/java/com/andharness/ru/data/db/AppDatabase.kt",
    )

    private val DELEGATE_VERSION = Regex("RoomOpenHelper\\.Delegate\\((\\d+)\\)")
    private val SOURCE_VERSION = Regex("version\\s*=\\s*(\\d+)")

    fun locate(): File? {
        val candidates = KAPT_ROOT_CANDIDATES
            .map(::File)
            .filter { it.isDirectory }
            .flatMap { root ->
                root.listFiles().orEmpty().mapNotNull { variant ->
                    File(variant, GENERATED_IMPL_RELATIVE).takeIf { it.isFile }
                }
            }
        if (candidates.isEmpty()) return null

        val currentVersion = currentDatabaseVersion()
        if (currentVersion != null) {
            candidates
                .filter { file ->
                    DELEGATE_VERSION.find(file.readText())
                        ?.groupValues?.get(1)?.toIntOrNull() == currentVersion
                }
                .maxByOrNull { it.lastModified() }
                ?.let { return it }
        }
        return candidates.maxByOrNull { it.lastModified() }
    }

    /** Version declared in `AppDatabase.kt`, or `null` when the source cannot be found. */
    private fun currentDatabaseVersion(): Int? = APP_DATABASE_CANDIDATES
        .map(::File)
        .firstOrNull { it.isFile }
        ?.readText()
        ?.let { SOURCE_VERSION.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
