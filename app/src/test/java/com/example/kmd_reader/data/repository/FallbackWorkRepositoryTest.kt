package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackWorkRepositoryTest {
    @Test
    fun listWorksUsesPrimaryWhenPrimaryHasData() = runTest {
        val primaryWork = MockWorks.works.first { it.id == "glass-rail" }
        val repository = FallbackWorkRepository(
            primary = FakeWorkRepository(works = listOf(primaryWork)),
            fallback = FakeWorkRepository(works = MockWorks.works)
        )

        val works = repository.listWorks(refresh = true)

        assertEquals(listOf("glass-rail"), works.map { it.id })
    }

    @Test
    fun listWorksFallsBackWhenPrimaryIsEmpty() = runTest {
        val repository = FallbackWorkRepository(
            primary = FakeWorkRepository(works = emptyList()),
            fallback = FakeWorkRepository(works = MockWorks.works)
        )

        val works = repository.listWorks(refresh = true)

        assertEquals(MockWorks.works.size, works.size)
    }

    @Test
    fun listIssuesFallsBackWhenPrimaryThrows() = runTest {
        val repository = FallbackWorkRepository(
            primary = FakeWorkRepository(throwOnIssues = true),
            fallback = FakeWorkRepository(issues = MockWorks.issues)
        )

        val issues = repository.listIssues(workId = "glass-rail", refresh = true)

        assertTrue(issues.isNotEmpty())
    }
}

private class FakeWorkRepository(
    private val works: List<Work> = emptyList(),
    private val issues: Map<String, List<ScriptIssue>> = emptyMap(),
    private val throwOnIssues: Boolean = false
) : WorkRepository {
    override suspend fun listWorks(refresh: Boolean): List<Work> = works

    override suspend fun getWork(id: String, refresh: Boolean): Work? =
        works.firstOrNull { it.id == id }

    override suspend fun listIssues(workId: String, refresh: Boolean): List<ScriptIssue> {
        if (throwOnIssues) {
            error("issue source unavailable")
        }
        return issues[workId].orEmpty()
    }
}
