package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.andharness.ru.data.db.entity.ExactAlarmEntity

@Dao
interface ExactAlarmDao {
    @Query("SELECT * FROM exact_alarms ORDER BY atMs, id")
    suspend fun getAll(): List<ExactAlarmEntity>

    @Query("SELECT * FROM exact_alarms WHERE id = :id")
    suspend fun getById(id: Long): ExactAlarmEntity?

    @Insert
    suspend fun insert(entity: ExactAlarmEntity): Long

    @Update
    suspend fun update(entity: ExactAlarmEntity)

    @Query("UPDATE exact_alarms SET requestCode = :requestCode WHERE id = :id")
    suspend fun setRequestCode(id: Long, requestCode: Int)

    @Query("UPDATE exact_alarms SET fired = :fired WHERE id = :id")
    suspend fun setFired(id: Long, fired: Boolean)

    @Query("DELETE FROM exact_alarms WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
