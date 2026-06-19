package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LocalLibraryDao {
    @Query("SELECT * FROM local_library WHERE workId = :workId")
    suspend fun getByWorkId(workId: String): LocalLibraryEntity?

    @Query("SELECT * FROM local_library WHERE onShelf = 1 ORDER BY COALESCE(lastReadAt, importedAt, 0) DESC")
    suspend fun getShelf(): List<LocalLibraryEntity>

    @Query("SELECT * FROM local_library WHERE lastReadAt IS NOT NULL ORDER BY lastReadAt DESC")
    suspend fun getHistory(): List<LocalLibraryEntity>

    // 用 @Upsert 而非 @Insert(REPLACE)：REPLACE 是先删后插，会触发 local_revisions /
    // local_drafts 的 ON DELETE CASCADE，导致 updateProgress / setOnShelf 把子表的草稿
    // 和 revision 全部抹掉。@Upsert 编译为 INSERT … ON CONFLICT(workId) DO UPDATE，
    // 原地更新，不触发级联删除。
    @Upsert
    suspend fun upsert(entity: LocalLibraryEntity)

    @Query("DELETE FROM local_library WHERE workId = :workId")
    suspend fun deleteByWorkId(workId: String)

    @Query("DELETE FROM local_library")
    suspend fun clear()
}
