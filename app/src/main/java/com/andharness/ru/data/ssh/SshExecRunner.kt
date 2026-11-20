package com.andharness.ru.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/**
 * Exec/stream-drain concern of the SSH engine: runs one command over an exec channel and drains
 * stdout/stderr to EOF, bounded by a deadline.
 *
 * **Nothing is interactive.** No PTY is requested, so a command that wants a password prompt
 * (`sudo`) fails instead of hanging.
 */
internal class SshExecRunner {

    fun run(session: Session, command: String, timeoutSec: Int): SshExecOutcome {
        val channel = session.openChannel("exec") as ChannelExec
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        try {
            channel.setCommand(command)
            channel.setErrStream(stderr)
            // BUG-025: the stdout pipe MUST be installed *before* the channel connects.
            // `Channel.getInputStream()` creates the PipedInputStream and binds it as the channel's
            // data sink (it logs a warning when called after connect). Until it is bound, incoming
            // SSH_MSG_CHANNEL_DATA hits a null `io.out`: `IO.put` throws an NPE that `Channel.write`
            // silently swallows, so the bytes are gone. For a fast command (`echo meha-ok`) the whole
            // output arrived between `connect()` and the reader's first use of `inputStream`, which is
            // exactly why stdout came back empty while stderr (bound via `setErrStream` before
            // connect) survived. Grabbing the stream up front closes that window.
            val stdoutStream = channel.inputStream
            channel.connect((timeoutSec * 1000).coerceIn(MIN_CONNECT_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS))
            val reader = Thread {
                try {
                    // Blocking read until EOF — never an `available()` poll (see [drainToEof]).
                    drainToEof(stdoutStream, stdout)
                } catch (_: Exception) {
                    // Stream closed by the timeout path below — the outcome records the timeout.
                }
            }
            reader.isDaemon = true
            reader.start()
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            // Wait until the server has closed the channel (all data/exit-status/EOF packets are
            // processed) but never past the deadline. `isClosed` implies `isEOF` in JSch (close()
            // sets both), so checking both is belt-and-braces.
            while ((!channel.isClosed || !channel.isEOF) && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            val timedOut = !channel.isClosed
            val exitStatus = if (timedOut) -1 else channel.exitStatus
            // Drain stdout to EOF *before* tearing the channel down: disconnecting first can discard
            // the tail of the output. Only a timeout needs an early disconnect to unblock the reader.
            if (timedOut) runCatching { channel.disconnect() }
            runCatching { reader.join(READER_JOIN_MS) }
            if (reader.isAlive) reader.interrupt()
            runCatching { channel.disconnect() }
            val stdoutText = stdout.toByteArray().toString(Charsets.UTF_8)
            val stderrText = stderr.toByteArray().toString(Charsets.UTF_8)
            return SshExecOutcome(
                stdout = render(stdoutText),
                stderr = render(stderrText),
                exitStatus = exitStatus,
                timedOut = timedOut,
                stdoutTruncated = stdoutText.length > MAX_OUTPUT_CHARS,
                stderrTruncated = stderrText.length > MAX_OUTPUT_CHARS
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SshFailure("Выполнение команды было прервано.", SshFailure.Kind.TIMEOUT)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    private fun render(text: String): String {
        return if (text.length > MAX_OUTPUT_CHARS) {
            text.substring(0, MAX_OUTPUT_CHARS) + "\n… (вывод обрезан)"
        } else {
            text
        }
    }

    private companion object {
        const val MIN_CONNECT_TIMEOUT_MS = 5_000
        const val MAX_CONNECT_TIMEOUT_MS = 30_000

        const val POLL_INTERVAL_MS = 50L
        const val READER_JOIN_MS = 1_000L

        /** Per-stream cap of what a tool result may carry back to the model. */
        const val MAX_OUTPUT_CHARS = 24_000
    }
}
