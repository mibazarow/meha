package com.andharness.ru.data.rag

import com.andharness.ru.data.db.dao.RagChunkDao
import com.andharness.ru.data.db.entity.RagChunkEntity
import com.andharness.ru.data.tools.documents.DocumentFormatDetector
import com.andharness.ru.data.tools.documents.DocumentTextExtractor
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.rag.RagChunkHit
import com.andharness.ru.domain.rag.RagIndexReport
import com.andharness.ru.domain.rag.RagQueryResult
import com.andharness.ru.domain.rag.RagRepository
import com.andharness.ru.domain.rag.RagSourceInfo
import com.andharness.ru.domain.retrieval.TextChunker
import com.andharness.ru.domain.retrieval.TextRetriever
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local RAG index over files (update.md idea #4).
 *
 * Indexing reuses the document tooling's extraction ([DocumentTextExtractor]) and chunks text with
 * [TextChunker]; only extracted chunks are persisted, never whole files. Retrieval ranks chunks with
 * the shared lexical+synonym BM25 of [TextRetriever], so the same "semantic-ish" mechanism that
 * powers Tool-RAG and memory recall is what answers document questions — offline, with no model.
 *
 * Freshness: each row stores the source file's `lastModified()` in `updatedAt`; re-indexing a source
 * whose mtime is unchanged is skipped unless `force` is set. Both file reads and database writes run
 * on [Dispatchers.IO].
 *
 * Bound: [MAX_FILES_PER_RUN] files per call and [MAX_CHUNKS_FOR_QUERY] chunks per query keep memory
 * predictable on a phone; hitting either is reported honestly instead of silently dropped.
 *
 * Official references:
 * - `java.io.File#lastModified()` — https://developer.android.com/reference/java/io/File#lastModified()
 * - Room `@Transaction` — https://developer.android.com/training/data-storage/room/transactions
 */
@Singleton
class RagIndexer @Inject constructor(
    private val dao: RagChunkDao
) : RagRepository {

    override suspend fun index(
        root: File,
        path: String,
        recursive: Boolean,
        force: Boolean
    ): RagIndexReport = withContext(Dispatchers.IO) {
        val target = try {
            resolveSafePath(root, path)
        } catch (e: IllegalArgumentException) {
            return@withContext failed(e.message ?: "Некорректный путь «$path».")
        }
        if (!target.exists()) return@withContext failed("Не найдено: «$path».")

        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val candidates = collectFiles(target, recursive, notes)
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
        if (candidates.isEmpty()) {
            return@withContext RagIndexReport(0, 0, 0, 0, emptyList(), emptyList(), notes)
        }

        var filesIndexed = 0
        var filesSkipped = 0
        var filesFailed = 0
        var chunksStored = 0
        val truncated = mutableListOf<String>()

        for (file in candidates) {
            val relativePath = relativePath(root, file)
            val format = DocumentFormatDetector.detect(file)
            if (!DocumentTextExtractor.isIndexable(format)) {
                filesFailed++
                errors += "$relativePath: формат ${format.name} не индексируется"
                continue
            }
            if (!force && dao.sourceMtime(relativePath) == file.lastModified()) {
                filesSkipped++
                continue
            }
            when (val extracted = DocumentTextExtractor.extract(file)) {
                is DocumentTextExtractor.Extracted.Failure -> {
                    filesFailed++
                    errors += "$relativePath: ${extracted.message}"
                }
                is DocumentTextExtractor.Extracted.Success -> {
                    if (extracted.text.isBlank()) {
                        filesFailed++
                        errors += "$relativePath: текст не найден"
                        continue
                    }
                    val chunks = TextChunker.chunk(extracted.text)
                    if (chunks.isEmpty()) {
                        filesFailed++
                        errors += "$relativePath: не удалось разбить текст на фрагменты"
                        continue
                    }
                    val mtime = file.lastModified()
                    val size = file.length()
                    dao.replaceSource(
                        relativePath,
                        chunks.map { chunk ->
                            RagChunkEntity(
                                sourcePath = relativePath,
                                sourceType = extracted.format.name.lowercase(),
                                chunkIndex = chunk.index,
                                text = chunk.text,
                                updatedAt = mtime,
                                sizeBytes = size
                            )
                        }
                    )
                    if (extracted.truncated) truncated += relativePath
                    filesIndexed++
                    chunksStored += chunks.size
                }
            }
        }
        RagIndexReport(
            filesIndexed = filesIndexed,
            filesSkipped = filesSkipped,
            filesFailed = filesFailed,
            chunksStored = chunksStored,
            errors = errors,
            truncatedFiles = truncated,
            notes = notes
        )
    }

    override suspend fun query(
        query: String,
        limit: Int,
        pathPrefix: String?
    ): RagQueryResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext RagQueryResult(emptyList(), 0, "Пустой запрос.")
        }
        val prefix = pathPrefix?.trim()?.takeIf { it.isNotEmpty() }
        val rows = if (prefix == null) dao.all() else dao.byPathPrefix(prefix)
        if (rows.isEmpty()) {
            return@withContext RagQueryResult(
                emptyList(),
                0,
                if (prefix == null) {
                    "Индекс пуст — сначала вызови rag_index."
                } else {
                    "В индексе нет файлов с путём «$prefix»."
                }
            )
        }
        val note = if (rows.size > MAX_CHUNKS_FOR_QUERY) {
            "Индекс большой: поиск ограничен первыми $MAX_CHUNKS_FOR_QUERY фрагментами."
        } else {
            null
        }
        val searched = rows.take(MAX_CHUNKS_FOR_QUERY)
        val docs = searched.map { row ->
            TextRetriever.Doc(id = key(row), tokens = TextRetriever.tokenize(row.text))
        }
        val hits = TextRetriever.bm25(trimmed, docs, limit.coerceAtLeast(1))
        if (hits.isEmpty()) {
            return@withContext RagQueryResult(
                emptyList(),
                searched.size,
                "Прямых совпадений по запросу не найдено."
            )
        }
        val byKey = searched.associateBy { key(it) }
        val ranked = hits.mapNotNull { hit ->
            byKey[hit.id]?.let { row ->
                RagChunkHit(
                    sourcePath = row.sourcePath,
                    sourceType = row.sourceType,
                    chunkIndex = row.chunkIndex,
                    text = row.text,
                    score = hit.score,
                    updatedAt = row.updatedAt,
                    sizeBytes = row.sizeBytes
                )
            }
        }
        RagQueryResult(ranked, searched.size, note)
    }

    override suspend fun stats(): List<RagSourceInfo> = withContext(Dispatchers.IO) {
        dao.sourceSummary().map { RagSourceInfo(it.sourcePath, it.chunkCount, it.mtime) }
    }

    override suspend fun clearSource(sourcePath: String) = withContext(Dispatchers.IO) {
        dao.deleteBySource(sourcePath)
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    /** Depth-first file collection with a hard cap; hitting the cap is reported in [notes]. */
    private fun collectFiles(target: File, recursive: Boolean, notes: MutableList<String>): List<File> {
        if (target.isFile) return listOf(target)
        val out = ArrayList<File>()
        val directory = ArrayDeque<File>()
        directory.add(target)
        while (directory.isNotEmpty()) {
            val dir = directory.removeFirst()
            val entries = dir.listFiles()?.sortedBy { it.name } ?: continue
            for (entry in entries) {
                if (out.size >= MAX_FILES_PER_RUN) {
                    notes += "Достигнут предел $MAX_FILES_PER_RUN файлов за один проход — остальные пропущены."
                    return out
                }
                if (entry.isDirectory) {
                    if (recursive) directory.add(entry)
                } else {
                    out += entry
                }
            }
        }
        return out
    }

    /** Root-relative, `/`-separated path — the stable citation and freshness key. */
    private fun relativePath(root: File, file: File): String =
        file.canonicalFile.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')

    private fun key(row: RagChunkEntity): String = "${row.sourcePath}\u0000${row.chunkIndex}"

    private fun failed(message: String) =
        RagIndexReport(0, 0, 1, 0, listOf(message), emptyList())

    private companion object {
        const val MAX_FILES_PER_RUN = 2_000
        const val MAX_CHUNKS_FOR_QUERY = 20_000
    }
}
