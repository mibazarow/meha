package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.RestartStateEntity

/** Persistence for the single restart-state row (`id = 1`). */
@Dao
interface RestartStateDao {

    @Query("SELECT * FROM restart_state WHERE id = 1")
    suspend fun get(): RestartStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RestartStateEntity)
}
