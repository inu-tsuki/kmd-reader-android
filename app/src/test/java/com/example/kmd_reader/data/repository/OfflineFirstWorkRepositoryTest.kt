package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.local.ScriptIssueDao
import com.example.kmd_reader.data.local.ScriptIssueEntity
import com.example.kmd_reader.data.local.WorkDao
import com.example.kmd_reader.data.local.WorkEntity
import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.data.remote.KmdCommunityApi
import com.example.kmd_reader.data.remote.dto.CommentSummaryDto
import com.example.kmd_reader.data.remote.dto.KmdScriptDto
import com.example.kmd_reader.data.remote.dto.KmdScriptRevisionDto
import com.example.kmd_reader.data.remote.dto.ReviewRequestDto
import com.example.kmd_reader.data.remote.dto.ReviewResponseDto
import com.example.kmd_reader.data.remote.dto.ScriptIssueDto
import com.example.kmd_reader.data.remote.dto.WorkDto
import com.example.kmd_reader.data.remote.dto.WorkStatsDto
import com.example.kmd_reader.domain.model.IssueSource
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.WorkSourceType
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineFirstWorkRepositoryTest {
    @Test
    fun listWorksWhenNetworkSucceedsUpdatesCache() = runTest {
        val workDao = FakeWorkDao()
        val issueDao = FakeScriptIssueDao()
        val api = FakeKmdCommunityApi(works = listOf(remoteWork()))
        val repository = OfflineFirstWorkRepository(
            workDao = workDao,
            issueDao = issueDao,
            api = api,
            nowMillis = { 42L }
        )

        val works = repository.listWorks(refresh = true)

        assertEquals(1, works.size)
        assertEquals("glass-rail", works.single().id)
        assertEquals(WorkSourceType.Remote, works.single().sourceType)
        assertEquals(PresentationMode.Stage, works.single().presentation.mode)
        assertEquals("/works/glass-rail/source", works.single().script.activeRevision.sourceUrl)
        assertEquals(42L, requireNotNull(workDao.getById("glass-rail")).syncedAt)
    }

    @Test
    fun listWorksWhenNetworkFailsReturnsCachedWorks() = runTest {
        val cachedWork = MockWorks.works.first { it.id == "rain-city" }
        val workDao = FakeWorkDao(
            initialWorks = listOf(cachedWork.toEntity(syncedAt = 1L))
        )
        val repository = OfflineFirstWorkRepository(
            workDao = workDao,
            issueDao = FakeScriptIssueDao(),
            api = FakeKmdCommunityApi(failWorks = true),
            nowMillis = { 42L }
        )

        val works = repository.listWorks(refresh = true)

        assertEquals(1, works.size)
        assertEquals("rain-city", works.single().id)
        assertEquals(cachedWork.title, works.single().title)
    }

    @Test
    fun listIssuesWhenNetworkSucceedsReplacesCache() = runTest {
        val issueDao = FakeScriptIssueDao(
            initialIssues = listOf(
                ScriptIssueEntity(
                    id = "old-issue",
                    workId = "glass-rail",
                    severity = "Info",
                    source = "Parser",
                    location = "old",
                    message = "old",
                    suggestion = "old",
                    syncedAt = 1L
                )
            )
        )
        val repository = OfflineFirstWorkRepository(
            workDao = FakeWorkDao(),
            issueDao = issueDao,
            api = FakeKmdCommunityApi(issues = listOf(remoteIssue())),
            nowMillis = { 99L }
        )

        val issues = repository.listIssues(workId = "glass-rail", refresh = true)

        assertEquals(1, issues.size)
        assertEquals("issue-glass-rail-1", issues.single().id)
        assertEquals(IssueSource.Performance, issues.single().source)
        assertEquals(99L, issueDao.getByWorkId("glass-rail").single().syncedAt)
    }

    @Test
    fun getWorkSourceReadsActiveKmdSourceFromApi() = runTest {
        val repository = OfflineFirstWorkRepository(
            workDao = FakeWorkDao(),
            issueDao = FakeScriptIssueDao(),
            api = FakeKmdCommunityApi(source = "Glass Rail\n\nsource from api"),
            nowMillis = { 99L }
        )

        val source = repository.getWorkSource(workId = "glass-rail", refresh = true)

        assertEquals("Glass Rail\n\nsource from api", source)
    }

    private fun remoteWork(): WorkDto {
        return WorkDto(
            id = "glass-rail",
            title = "Glass Rail",
            authorName = "Noah",
            description = "A landscape cinematic script.",
            tags = listOf("cinematic", "landscape"),
            presentationMode = "stage",
            orientationHint = "landscape",
            aspectRatio = "16:9",
            lifecycleStatus = "submitted",
            interactionLevel = "light_interactive",
            previewMode = "clip",
            estimatedDurationSec = 360,
            coverUrl = "/assets/covers/glass-rail.jpg",
            script = KmdScriptDto(
                activeRevisionId = "rev-1",
                revisions = listOf(
                    KmdScriptRevisionDto(
                        id = "rev-1",
                        label = "Submitted stage preview script",
                        sourceUrl = "/works/glass-rail/source",
                        mimeType = "text/x-kmd",
                        kmdVersion = "0.1",
                        runtimeVersion = "0.2-preview",
                        createdAt = "2026-05-20T00:00:00.000Z"
                    )
                )
            ),
            stats = WorkStatsDto(scenes = 9, lines = 132, effects = 37),
            commentSummary = CommentSummaryDto(
                count = 6,
                preview = listOf("The wide-screen mood is strong.")
            )
        )
    }

    private fun remoteIssue(): ScriptIssueDto {
        return ScriptIssueDto(
            id = "issue-glass-rail-1",
            workId = "glass-rail",
            severity = "warning",
            source = "performance",
            location = "scene: bridge",
            message = "Multiple high-intensity stage movements appear in a short interval.",
            suggestion = "Reduce default motion strength for mobile preview playback."
        )
    }
}

private class FakeWorkDao(
    initialWorks: List<WorkEntity> = emptyList()
) : WorkDao {
    private val works = initialWorks.associateBy { it.id }.toMutableMap()

    override suspend fun getAll(): List<WorkEntity> {
        return works.values.sortedBy { it.title.lowercase() }
    }

    override suspend fun getById(id: String): WorkEntity? {
        return works[id]
    }

    override suspend fun upsert(work: WorkEntity) {
        works[work.id] = work
    }

    override suspend fun upsertAll(works: List<WorkEntity>) {
        works.forEach { upsert(it) }
    }

    override suspend fun deleteById(id: String) {
        works.remove(id)
    }

    override suspend fun clear() {
        works.clear()
    }
}

private class FakeScriptIssueDao(
    initialIssues: List<ScriptIssueEntity> = emptyList()
) : ScriptIssueDao {
    private val issues = initialIssues.associateBy { it.id }.toMutableMap()

    override suspend fun getByWorkId(workId: String): List<ScriptIssueEntity> {
        return issues.values.filter { it.workId == workId }.sortedBy { it.id }
    }

    override suspend fun upsertAll(issues: List<ScriptIssueEntity>) {
        issues.forEach { this.issues[it.id] = it }
    }

    override suspend fun clearForWork(workId: String) {
        issues.entries.removeIf { it.value.workId == workId }
    }

    override suspend fun clear() {
        issues.clear()
    }
}

private class FakeKmdCommunityApi(
    private val works: List<WorkDto> = emptyList(),
    private val work: WorkDto? = null,
    private val issues: List<ScriptIssueDto> = emptyList(),
    private val source: String = "",
    private val failWorks: Boolean = false
) : KmdCommunityApi {
    override suspend fun getWorks(): List<WorkDto> {
        if (failWorks) {
            error("network down")
        }
        return works
    }

    override suspend fun getWork(id: String): WorkDto {
        return work ?: works.first { it.id == id }
    }

    override suspend fun getWorkSource(id: String): ResponseBody {
        return source.toResponseBody("text/x-kmd; charset=utf-8".toMediaType())
    }

    override suspend fun getRevisionSource(id: String, revisionId: String): ResponseBody {
        return getWorkSource(id)
    }

    override suspend fun getIssues(id: String): List<ScriptIssueDto> {
        return issues.filter { it.workId == id }
    }

    override suspend fun submitReview(request: ReviewRequestDto): ReviewResponseDto {
        return ReviewResponseDto(
            id = "review-001",
            workId = request.workId,
            decision = request.decision,
            accepted = true
        )
    }
}
