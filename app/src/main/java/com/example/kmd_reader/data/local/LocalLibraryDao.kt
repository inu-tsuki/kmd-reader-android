package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalLibraryDao {
    @Query("SELECT * FROM local_library WHERE workId = :workId")
    suspend fun getByWorkId(workId: String): LocalLibraryEntity?

    @Query("SELECT * FROM local_library WHERE onShelf = 1 ORDER BY COALESCE(lastReadAt, importedAt, 0) DESC")
    suspend fun getShelf(): List<LocalLibraryEntity>

    @Query("SELECT * FROM local_library WHERE lastReadAt IS NOT NULL ORDER BY lastReadAt DESC")
    suspend fun getHistory(): List<LocalLibraryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalLibraryEntity)

    @Query("DELETE FROM local_library WHERE workId = :workId")
    suspend fun deleteByWorkId(workId: String)

    @Query("DELETE FROM local_library")
    suspend fun clear()
}
