package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.local.ScriptIssueDao
import com.example.kmd_reader.data.local.WorkDao
import com.example.kmd_reader.data.remote.KmdCommunityApi
import com.example.kmd_reader.data.remote.dto.ReviewRequestDto
import com.example.kmd_reader.data.remote.dto.ReviewResponseDto
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work

class OfflineFirstWorkRepository(
    private val workDao: WorkDao,
    private val issueDao: ScriptIssueDao,
    private val api: KmdCommunityApi,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun listWorks(refresh: Boolean = true): List<Work> {
        val cached = workDao.getAll().map { it.toDomain() }
        if (!refresh) {
            return cached
        }

        return runCatching {
            val now = nowMillis()
            workDao.upsertAll(api.getWorks().map { it.toEntity(now) })
            workDao.getAll().map { it.toDomain() }
        }.getOrElse {
            cached
        }
    }

    suspend fun getWork(id: String, refresh: Boolean = true): Work? {
        val cached = workDao.getById(id)?.toDomain()
        if (!refresh) {
            return cached
        }

        return runCatching {
            val now = nowMillis()
            workDao.upsert(api.getWork(id).toEntity(now))
            workDao.getById(id)?.toDomain()
        }.getOrElse {
            cached
        }
    }

    suspend fun listIssues(workId: String, refresh: Boolean = true): List<ScriptIssue> {
        val cached = issueDao.getByWorkId(workId).map { it.toDomain() }
        if (!refresh) {
            return cached
        }

        return runCatching {
            val now = nowMillis()
            issueDao.replaceForWork(workId, api.getIssues(workId).map { it.toEntity(now) })
            issueDao.getByWorkId(workId).map { it.toDomain() }
        }.getOrElse {
            cached
        }
    }

    suspend fun submitReview(request: ReviewRequestDto): ReviewResponseDto {
        return api.submitReview(request)
    }
}
