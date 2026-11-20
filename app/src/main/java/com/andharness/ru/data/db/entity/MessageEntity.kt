package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatId: Long,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val createdAt: Long,
    val isStreaming: Boolean = false,
    /**
     * Optional structured payload for a `tool` row (e.g. the `device_search` result cards). The
     * chat renders it as a rich preview; `null` for every ordinary user/assistant message. Stored as
     * opaque JSON so the schema stays generic (see `DeviceSearchPayload`). Added in v40 -> v41.
     */
    val payload: String? = null
)
