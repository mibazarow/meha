package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.common.Hashing
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/** `files_hash`: streams MD5 / SHA-1 / SHA-256 over a file. */
internal class FilesHashToolHandler(private val io: FileToolIo) : ToolHandler {
    override val name = "files_hash"
    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
        val file = File(io.path(arguments, "path"))
        if (!file.isFile) return ToolResult(false, "Не файл: ${file.name}.")
        // Only whitelisted JCA names ever reach MessageDigest.getInstance; a user-supplied
        // string is never passed through directly (an unknown name would otherwise throw
        // NoSuchAlgorithmException instead of an honest, actionable error).
        // java.security.MessageDigest —
        // https://developer.android.com/reference/java/security/MessageDigest
        val requested = (arguments["algorithm"] as? JsonPrimitive)?.content
            ?.trim()?.uppercase()?.replace("-", "").orEmpty()
        val canonical = when (requested) {
            "" , "SHA256" -> "SHA-256"
            "MD5" -> "MD5"
            "SHA1" -> "SHA-1"
            else -> return ToolResult(
                false,
                "Недопустимый алгоритм: «${(arguments["algorithm"] as? JsonPrimitive)?.content}». " +
                    "Допустимо: MD5, SHA-1, SHA-256."
            )
        }
        val md = MessageDigest.getInstance(canonical)
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = Hashing.toHex(md.digest())
        ToolResult(true, "$canonical ${file.name}: $hex")
    } catch (e: Exception) {
        ToolResult(false, "Ошибка: ${e.message}")
    }
    }
}
