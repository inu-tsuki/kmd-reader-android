package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalRevisionDao {
    // 提交不可变：用 ABORT 而非 REPLACE——重复 id 写入应抛冲突异常，
    // 强制调用方生成新 id（append-only，r3-local-reader-plan.md §2.7）。
    // Room 无子表依赖，ABORT 不触发级联问题。
    // 最新提交 = 最新可播放版本，无论 syncState（本地领先也算最新可播放）。
    // 对齐 r3-local-reader-plan.md §2.7 播放优先级。
    @Query("SELECT * FROM local_revisions WHERE workId = :workId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestRevision(workId: String): LocalRevisionEntity?

    @Query("SELECT * FROM local_revisions WHERE workId = :workId AND contentHash = :contentHash LIMIT 1")
    suspend fun findByContentHash(workId: String, contentHash: String): LocalRevisionEntity?

    @Query("SELECT * FROM local_revisions WHERE workId = :workId ORDER BY createdAt DESC")
    suspend fun getRevisionsForWork(workId: String): List<LocalRevisionEntity>

    // append-only：ABORT 意味着同 id 重复 insert 抛异常，不静默覆写。
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: LocalRevisionEntity)

    @Query("DELETE FROM local_revisions WHERE workId = :workId")
    suspend fun clearForWork(workId: String)

    @Query("DELETE FROM local_revisions")
    suspend fun clear()
}