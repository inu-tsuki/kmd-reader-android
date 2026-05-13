package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WorkDao {
    @Query("SELECT * FROM works ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<WorkEntity>

    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun getById(id: String): WorkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(work: WorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(works: List<WorkEntity>)

    @Query("DELETE FROM works WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM works")
    suspend fun clear()
}
