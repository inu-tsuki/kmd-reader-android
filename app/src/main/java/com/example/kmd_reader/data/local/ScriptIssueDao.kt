package com.example.kmd_reader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ScriptIssueDao {
    @Query("SELECT * FROM script_issues WHERE workId = :workId ORDER BY id")
    suspend fun getByWorkId(workId: String): List<ScriptIssueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(issues: List<ScriptIssueEntity>)

    @Query("DELETE FROM script_issues WHERE workId = :workId")
    suspend fun clearForWork(workId: String)

    @Transaction
    suspend fun replaceForWork(workId: String, issues: List<ScriptIssueEntity>) {
        clearForWork(workId)
        upsertAll(issues)
    }

    @Query("DELETE FROM script_issues")
    suspend fun clear()
}
