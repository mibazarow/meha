package com.andharness.ru.data.update

import android.content.Context
import com.andharness.ru.domain.update.UpdateManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-facing wrapper around [ApkDownloader] that owns the cache destination and never throws:
 * failures come back as [Result.failure] with a Russian message.
 */
@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    /**
     * Downloads [manifest]'s APK into `cacheDir/updates/`, verifying size and SHA-256. Runs the
     * blocking core on [Dispatchers.IO]; never throws.
     */
    suspend fun downloadApk(
        manifest: UpdateManifest,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val destDir = File(context.cacheDir, "updates")
            val file = ApkDownloader.downloadTo(
                client = okHttpClient,
                url = manifest.url,
                expectedSha256 = manifest.sha256,
                expectedSizeBytes = manifest.sizeBytes,
                destDir = destDir,
                onProgress = onProgress,
            )
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
