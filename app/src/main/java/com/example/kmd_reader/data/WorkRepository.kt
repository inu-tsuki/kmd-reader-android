package com.example.kmd_reader.data

import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work

interface WorkRepository {
    suspend fun listWorks(refresh: Boolean = true): List<Work>
    suspend fun getWork(id: String, refresh: Boolean = true): Work?
    suspend fun listIssues(workId: String, refresh: Boolean = true): List<ScriptIssue>
}
