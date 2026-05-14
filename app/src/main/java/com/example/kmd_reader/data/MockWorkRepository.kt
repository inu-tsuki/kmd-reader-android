package com.example.kmd_reader.data

import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work

class MockWorkRepository : WorkRepository {
    override suspend fun listWorks(refresh: Boolean): List<Work> = MockWorks.works

    override suspend fun getWork(id: String, refresh: Boolean): Work? =
        MockWorks.works.firstOrNull { it.id == id }

    override suspend fun listIssues(workId: String, refresh: Boolean): List<ScriptIssue> =
        MockWorks.issues[workId].orEmpty()
}
