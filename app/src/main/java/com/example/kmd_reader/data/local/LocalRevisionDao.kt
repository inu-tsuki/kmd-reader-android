package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalRevisionDao {
    // 最新提交 = 最新可播放版本，无论 syncState（本地领先也算最新可播放）。
    // 对齐 r3-local-reader-plan.md §2.7 播放优先级。
    @Query("SELECT * FROM local_revisions WHERE workId = :workId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestRevision(workId: String): LocalRevisionEntity?

    @Query("SELECT * FROM local_revisions WHERE workId = :workId AND contentHash = :contentHash LIMIT 1")
    suspend fun findByContentHash(workId: String, contentHash: String): LocalRevisionEntity?

    @Query("SELECT * FROM local_revisions WHERE workId = :workId ORDER BY createdAt DESC")
    suspend fun getRevisionsForWork(workId: String): List<LocalRevisionEntity>

    // @Insert(REPLACE) 保留：revisions 无子表，REPLACE 不触发级联删；提交不可变语义由调用方保证。
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalRevisionEntity)

    @Query("DELETE FROM local_revisions WHERE workId = :workId")
    suspend fun clearForWork(workId: String)

    @Query("DELETE FROM local_revisions")
    suspend fun clear()
}