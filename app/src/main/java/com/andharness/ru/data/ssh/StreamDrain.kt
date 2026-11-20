package com.andharness.ru.data.ssh

import java.io.InputStream
import java.io.OutputStream

/**
 * Reads [input] up to EOF and copies everything into [output], returning the number of bytes copied.
 *
 * The loop blocks on [InputStream.read] (which returns `-1` at end of stream) and **never** consults
 * [InputStream.available]. Polling `available()`/a single `read()` is what loses data on a fast SSH
 * channel: the server may have finished writing (and closed) before the client looks, so the buffer
 * looks empty even though bytes are pending. Blocking read-until-EOF is the documented way to consume
 * a JSch `ChannelExec` stdout stream (see `Channel.getInputStream()` /
 * https://github.com/mwiede/jsch — "getInputStream() should be called before connect()").
 *
 * Kept Android-free on purpose so the logic is unit-testable on the JVM (`StreamDrainTest`).
 */
internal fun drainToEof(input: InputStream, output: OutputStream, bufferSize: Int = 8192): Long {
    require(bufferSize > 0) { "bufferSize must be positive" }
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
    }
    return total
}
