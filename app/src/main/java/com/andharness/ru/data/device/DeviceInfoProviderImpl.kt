package com.andharness.ru.data.device

import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.andharness.ru.domain.device.DeviceInfoProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DeviceInfoProvider] that reads static hardware/OS facts. Dynamic values (battery, free space)
 * are fetched on demand via the `device_info` tool instead of being baked into the prompt.
 */
@Singleton
class DeviceInfoProviderImpl @Inject constructor() : DeviceInfoProvider {

    override fun summary(): String {
        val sdk = Build.VERSION.SDK_INT
        val (totalGb, availableGb) = storage()
        return buildString {
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device codename: ${Build.DEVICE}")
            appendLine("Android version: ${Build.VERSION.RELEASE} (API $sdk)")
            if (totalGb > 0.0) {
                append("Storage: %.1f GB total, %.1f GB available".format(totalGb, availableGb))
            }
        }
    }

    private fun storage(): Pair<Double, Double> {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            total / (1024.0 * 1024 * 1024) to available / (1024.0 * 1024 * 1024)
        } catch (_: Exception) {
            0.0 to 0.0
        }
    }
}
