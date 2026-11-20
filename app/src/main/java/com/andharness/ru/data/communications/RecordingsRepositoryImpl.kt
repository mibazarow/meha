package com.andharness.ru.data.communications

import com.andharness.ru.data.db.dao.RecordingDao
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed [RecordingsRepository] over `recordings`.
 *
 * The DAO is `suspend` (Room runs it off the main thread, see
 * https://developer.android.com/training/data-storage/room/accessing-data), and the explicit
 * [Dispatchers.IO] keeps column encoding/decoding work off the caller's dispatcher.
 */
@Singleton
class RecordingsRepositoryImpl @Inject constructor(
    private val dao: RecordingDao
) : RecordingsRepository {

    override suspend fun list(limit: Int, status: TranscriptStatus?): List<Recording> =
        withContext(Dispatchers.IO) {
            val rows = if (status == null) dao.recent(limit) else dao.byStatus(status.name, limit)
            rows.map { it.toDomain() }
        }

    override suspend fun getById(id: Long): Recording? = withContext(Dispatchers.IO) {
        dao.get(id)?.toDomain()
    }

    override suspend fun recentForPerson(personId: Long, limit: Int): List<Recording> =
        withContext(Dispatchers.IO) {
            dao.byPerson(personId, limit).map { it.toDomain() }
        }

    override suspend fun insert(recording: Recording): Long = withContext(Dispatchers.IO) {
        dao.insert(recording.copy(id = 0).toEntity())
    }

    override suspend fun updateStatus(
        id: Long,
        status: TranscriptStatus,
        errorText: String?
    ): Int = withContext(Dispatchers.IO) {
        val reason = if (status == TranscriptStatus.FAILED) errorText else null
        dao.updateTranscriptStatus(id, status.name, reason, System.currentTimeMillis())
    }

    override suspend fun setKeepFlag(id: Long, keepFlag: Boolean): Int = withContext(Dispatchers.IO) {
        dao.setKeepFlag(id, keepFlag, System.currentTimeMillis())
    }

    override suspend fun delete(id: Long): Int = withContext(Dispatchers.IO) { dao.delete(id) }

    override suspend fun listPending(limit: Int): List<Recording> = list(limit, TranscriptStatus.PENDING)

    override suspend fun countByStatus(status: TranscriptStatus): Int = withContext(Dispatchers.IO) {
        dao.countByStatus(status.name)
    }
}
