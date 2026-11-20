package com.andharness.ru.data.communications.speech

/**
 * Pure builder for the `whisper-cli` command line. Android-free and JVM-testable.
 *
 * The bundled binary is whisper.cpp v1.9.4 `whisper-cli`. Its argument parser lives in
 * `examples/cli/cli.cpp` of revision `927cfce34f31707e17f2bff35c349632fb9e2c3a`:
 * https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/examples/cli/cli.cpp
 * - `-m`/`--model` file, `-f`/`--file` input audio, `-l`/`--language`, `-t`/`--threads`
 * - `-oj`/`--output-json` writes `<outPrefix>.json`; `-of`/`--output-file` sets `<outPrefix>`
 *   (documented as "output file path (without file extension)")
 * - `--vad` enables Voice Activity Detection; `-vm`/`--vad-model` supplies its model
 *
 * `-nt` (no timestamps) is deliberately never emitted: the JSON parser relies on the per-segment
 * timestamps/offsets.
 */
object WhisperArgs {

    /**
     * Ordered CLI arguments (without the executable itself). JSON output is always enabled via
     * `-oj` + `-of <outPrefix>`.
     *
     * @param modelPath absolute path to the `.bin` model
     * @param wavPath absolute path to the 16 kHz mono PCM16 WAV
     * @param outPrefix output path **without** extension; whisper.cpp appends `.json`
     * @param threads worker threads (caller should already cap this to the device's safe range)
     * @param language spoken language or `"auto"` for auto-detection
     * @param vadModelPath optional Silero VAD model; when non-null `--vad -vm <path>` is appended
     */
    fun buildArgs(
        modelPath: String,
        wavPath: String,
        outPrefix: String,
        threads: Int,
        language: String = "auto",
        vadModelPath: String? = null,
    ): List<String> {
        val args = mutableListOf(
            "-m", modelPath,
            "-f", wavPath,
            "-l", language,
            "-t", threads.toString(),
            "-oj",
            "-of", outPrefix,
        )
        if (vadModelPath != null) {
            args += "--vad"
            args += "-vm"
            args += vadModelPath
        }
        return args
    }
}
