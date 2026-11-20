package com.andharness.ru.data.tools.media

import java.util.Locale

/** Number formatting shared by the audio analysis / capture tools (US locale, two decimals). */
internal object AudioFormatting {

    fun formatDouble(value: Double): String = String.format(Locale.US, "%.2f", value)
}
