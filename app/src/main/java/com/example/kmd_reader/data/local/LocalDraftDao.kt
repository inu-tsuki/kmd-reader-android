package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalDraftDao {
    @Query("SELECT * FROM local_drafts WHERE workId = :workId ORDER BY updatedAt DESC")
    suspend fun getByWorkId(workId: String): List<LocalDraftEntity>

    @Query("SELECT * FROM local_drafts WHERE workId = :workId AND type = :type ORDER BY updatedAt DESC")
    suspend fun getByWorkIdAndType(workId: String, type: String): List<LocalDraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalDraftEntity)

    @Query("DELETE FROM local_drafts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_drafts WHERE workId = :workId")
    suspend fun clearForWork(workId: String)

    @Query("DELETE FROM local_drafts")
    suspend fun clear()
}
