package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalRevisionDao {
    @Query("SELECT * FROM local_revisions WHERE workId = :workId AND synced = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveLocalRevision(workId: String): LocalRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalRevisionEntity)

    @Query("DELETE FROM local_revisions WHERE workId = :workId")
    suspend fun clearForWork(workId: String)

    @Query("DELETE FROM local_revisions")
    suspend fun clear()
}
