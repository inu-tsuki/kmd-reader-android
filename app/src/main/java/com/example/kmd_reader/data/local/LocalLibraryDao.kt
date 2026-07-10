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

    // R3-G-rev3：字段级原子 UPDATE。旧实现用 getByWorkId → copy → upsert，
    // 在 toggle 和 updateProgress 并发时会互相覆盖对方的字段（toggle 覆盖 progress，
    // progress 覆盖 onShelf）。改为只更新各自字段，消除 read-modify-write 竞态。
    // durationMs/revisionId 用 COALESCE 保留已有值（null 表示当前事件未携带，
    // 不应清除已有基准——F4/F4-rev 语义）。
    @Query("""
        UPDATE local_library
        SET readingProgress = :progress,
            readingTimeMs = :timeMs,
            readingDurationMs = COALESCE(:durationMs, readingDurationMs),
            activeRevisionId = COALESCE(:revisionId, activeRevisionId),
            lastReadAt = :now
        WHERE workId = :workId
    """)
    suspend fun updateProgress(
        workId: String,
        progress: Float,
        timeMs: Long?,
        durationMs: Long?,
        now: Long,
        revisionId: String?
    ): Int

    @Query("UPDATE local_library SET onShelf = :onShelf WHERE workId = :workId")
    suspend fun setOnShelf(workId: String, onShelf: Boolean): Int

    @Query("DELETE FROM local_library WHERE workId = :workId")
    suspend fun deleteByWorkId(workId: String)

    @Query("DELETE FROM local_library")
    suspend fun clear()
}
