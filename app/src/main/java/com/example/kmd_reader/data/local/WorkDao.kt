package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface WorkDao {
    @Query("SELECT * FROM works ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<WorkEntity>

    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun getById(id: String): WorkEntity?

    // 用 @Upsert 而非 @Insert(REPLACE)：REPLACE 是先删后插，works 是 script_issues
    // 的父表（ON DELETE CASCADE），任何 work 字段更新（刷新 syncedAt、改
    // activeRevisionId 等）都会触发级联，把该 work 名下全部 script_issues 抹掉。
    // @Upsert 编译为 INSERT … ON CONFLICT(id) DO UPDATE，原地更新不触发级联。
    // 与 LocalLibraryDao 的修复同构（R3-A Finding 1）。
    @Upsert
    suspend fun upsert(work: WorkEntity)

    @Upsert
    suspend fun upsertAll(works: List<WorkEntity>)

    @Query("DELETE FROM works WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM works")
    suspend fun clear()
}
