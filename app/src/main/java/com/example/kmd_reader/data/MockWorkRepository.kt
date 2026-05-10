package com.example.kmd_reader.data

import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work

class MockWorkRepository : WorkRepository {
    override fun listWorks(): List<Work> = MockWorks.works

    override fun getWork(id: String): Work? = MockWorks.works.firstOrNull { it.id == id }

    override fun listIssues(workId: String): List<ScriptIssue> = MockWorks.issues[workId].orEmpty()
}
