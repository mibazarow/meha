package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
