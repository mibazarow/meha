package com.andharness.ru.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.andharness.ru.data.db.dao.ChatDao
import com.andharness.ru.data.db.dao.ChatMessageVectorDao
import com.andharness.ru.data.db.dao.CrashLogDao
import com.andharness.ru.data.db.dao.DeviceFactDao
import com.andharness.ru.data.db.dao.DeviceIndexVectorDao
import com.andharness.ru.data.db.dao.ExactAlarmDao
import com.andharness.ru.data.db.dao.MemoryEntityDao
import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.dao.MemoryVectorDao
import com.andharness.ru.data.db.dao.MessageDao
import com.andharness.ru.data.db.dao.NoteFtsDao
import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.AutomationEventDao
import com.andharness.ru.data.db.dao.AutomationRuleDao
import com.andharness.ru.data.db.dao.FolderWatchDao
import com.andharness.ru.data.db.dao.InteractionDao
import com.andharness.ru.data.db.dao.PersonAliasDao
import com.andharness.ru.data.db.dao.PersonDao
import com.andharness.ru.data.db.dao.PersonIdentityDao
import com.andharness.ru.data.db.dao.RagChunkDao
import com.andharness.ru.data.db.dao.RestartStateDao
import com.andharness.ru.data.db.dao.SafGrantDao
import com.andharness.ru.data.db.dao.ScheduledTaskDao
import com.andharness.ru.data.db.dao.SettingsDao
import com.andharness.ru.data.db.dao.LlmProviderDao
import com.andharness.ru.data.db.dao.SshServerDao
import com.andharness.ru.data.db.dao.ToolAuditDao
import com.andharness.ru.data.db.dao.TrashEntryDao
import com.andharness.ru.data.db.dao.RecordingDao
import com.andharness.ru.data.db.dao.SpeakerDao
import com.andharness.ru.data.db.dao.SpeakerVoiceEmbeddingDao
import com.andharness.ru.data.db.dao.StoredMessageDao
import com.andharness.ru.data.db.dao.StoredMessageFtsDao
import com.andharness.ru.data.db.dao.StoredMessageVectorDao
import com.andharness.ru.data.db.dao.TranscriptFtsDao
import com.andharness.ru.data.db.dao.TranscriptSegmentDao
import com.andharness.ru.data.db.dao.TranscriptVectorDao
import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.ChatMessageVectorEntity
import com.andharness.ru.data.db.entity.AutomationEventEntity
import com.andharness.ru.data.db.entity.AutomationRuleEntity
import com.andharness.ru.data.db.entity.CrashLogEntity
import com.andharness.ru.data.db.entity.DeviceFactEntity
import com.andharness.ru.data.db.entity.DeviceFactEventEntity
import com.andharness.ru.data.db.entity.DeviceIndexVectorEntity
import com.andharness.ru.data.db.entity.ExactAlarmEntity
import com.andharness.ru.data.db.entity.FolderWatchEntity
import com.andharness.ru.data.db.entity.FolderWatchEventEntity
import com.andharness.ru.data.db.entity.GenericEntity
import com.andharness.ru.data.db.entity.InteractionEntity
import com.andharness.ru.data.db.entity.LlmUsageEntity
import com.andharness.ru.data.db.entity.MemoryEntityLinkEntity
import com.andharness.ru.data.db.entity.MemoryPersonEntity
import com.andharness.ru.data.db.entity.MemoryVectorEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.NoteFtsEntity
import com.andharness.ru.data.db.entity.LlmProviderEntity
import com.andharness.ru.data.db.entity.PersonAliasEntity
import com.andharness.ru.data.db.entity.PersonEntity
import com.andharness.ru.data.db.entity.PersonIdentityEntity
import com.andharness.ru.data.db.entity.RagChunkEntity
import com.andharness.ru.data.db.entity.RestartStateEntity
import com.andharness.ru.data.db.entity.SafGrantEntity
import com.andharness.ru.data.db.entity.ScheduledTaskEntity
import com.andharness.ru.data.db.entity.SettingsEntity
import com.andharness.ru.data.db.entity.SshServerEntity
import com.andharness.ru.data.db.entity.TodoEntity
import com.andharness.ru.data.db.entity.ToolAuditEntity
import com.andharness.ru.data.db.entity.TrashEntryEntity
import com.andharness.ru.data.db.entity.RecordingEntity
import com.andharness.ru.data.db.entity.SpeakerEntity
import com.andharness.ru.data.db.entity.SpeakerVoiceEmbeddingEntity
import com.andharness.ru.data.db.entity.StoredMessageEntity
import com.andharness.ru.data.db.entity.StoredMessageFtsEntity
import com.andharness.ru.data.db.entity.StoredMessageVectorEntity
import com.andharness.ru.data.db.entity.TranscriptFtsEntity
import com.andharness.ru.data.db.entity.TranscriptSegmentEntity
import com.andharness.ru.data.db.entity.TranscriptVectorEntity

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        SettingsEntity::class,
        LlmProviderEntity::class,
        ScheduledTaskEntity::class,
        NoteEntity::class,
        NoteFtsEntity::class,
        TodoEntity::class,
        SafGrantEntity::class,
        TrashEntryEntity::class,
        FolderWatchEntity::class,
        FolderWatchEventEntity::class,
        DeviceFactEntity::class,
        DeviceFactEventEntity::class,
        ExactAlarmEntity::class,
        AutomationRuleEntity::class,
        AutomationEventEntity::class,
        CrashLogEntity::class,
        RestartStateEntity::class,
        PersonEntity::class,
        PersonIdentityEntity::class,
        InteractionEntity::class,
        PersonAliasEntity::class,
        SshServerEntity::class,
        ToolAuditEntity::class,
        LlmUsageEntity::class,
        RagChunkEntity::class,
        MemoryVectorEntity::class,
        MemoryPersonEntity::class,
        GenericEntity::class,
        MemoryEntityLinkEntity::class,
        RecordingEntity::class,
        TranscriptSegmentEntity::class,
        TranscriptFtsEntity::class,
        TranscriptVectorEntity::class,
        SpeakerEntity::class,
        SpeakerVoiceEmbeddingEntity::class,
        StoredMessageEntity::class,
        StoredMessageFtsEntity::class,
        StoredMessageVectorEntity::class,
        ChatMessageVectorEntity::class,
        DeviceIndexVectorEntity::class
    ],
    version = 42,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun llmProviderDao(): LlmProviderDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun assistDao(): AssistDao
    abstract fun noteFtsDao(): NoteFtsDao
    abstract fun safGrantDao(): SafGrantDao
    abstract fun trashEntryDao(): TrashEntryDao
    abstract fun folderWatchDao(): FolderWatchDao
    abstract fun deviceFactDao(): DeviceFactDao
    abstract fun exactAlarmDao(): ExactAlarmDao
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun automationEventDao(): AutomationEventDao
    abstract fun crashLogDao(): CrashLogDao
    abstract fun restartStateDao(): RestartStateDao
    abstract fun personDao(): PersonDao
    abstract fun personIdentityDao(): PersonIdentityDao
    abstract fun interactionDao(): InteractionDao
    abstract fun personAliasDao(): PersonAliasDao
    abstract fun sshServerDao(): SshServerDao
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun ragChunkDao(): RagChunkDao
    abstract fun memoryVectorDao(): MemoryVectorDao
    abstract fun memoryPersonDao(): MemoryPersonDao
    abstract fun memoryEntityDao(): MemoryEntityDao
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao
    abstract fun transcriptFtsDao(): TranscriptFtsDao
    abstract fun transcriptVectorDao(): TranscriptVectorDao
    abstract fun speakerDao(): SpeakerDao
    abstract fun speakerVoiceEmbeddingDao(): SpeakerVoiceEmbeddingDao
    abstract fun storedMessageDao(): StoredMessageDao
    abstract fun storedMessageFtsDao(): StoredMessageFtsDao
    abstract fun storedMessageVectorDao(): StoredMessageVectorDao
    abstract fun chatMessageVectorDao(): ChatMessageVectorDao
    abstract fun deviceIndexVectorDao(): DeviceIndexVectorDao
}
