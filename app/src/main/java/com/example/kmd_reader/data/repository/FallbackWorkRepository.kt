package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work

class FallbackWorkRepository(
    private val primary: WorkRepository,
    private val fallback: WorkRepository
) : WorkRepository {
    override suspend fun listWorks(refresh: Boolean): List<Work> {
        val primaryWorks = runCatching {
            primary.listWorks(refresh = refresh)
        }.getOrDefault(emptyList())

        return primaryWorks.ifEmpty {
            fallback.listWorks(refresh = false)
        }
    }

    override suspend fun getWork(id: String, refresh: Boolean): Work? {
        return runCatching {
            primary.getWork(id = id, refresh = refresh)
        }.getOrNull() ?: fallback.getWork(id = id, refresh = false)
    }

    override suspend fun listIssues(workId: String, refresh: Boolean): List<ScriptIssue> {
        val primaryIssues = runCatching {
            primary.listIssues(workId = workId, refresh = refresh)
        }.getOrDefault(emptyList())

        return primaryIssues.ifEmpty {
            fallback.listIssues(workId = workId, refresh = false)
        }
    }
}
