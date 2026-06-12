package com.example.kmd_reader.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kmd_reader.data.local.KmdReaderDatabase
import com.example.kmd_reader.data.remote.dto.ReviewRequestDto
import com.example.kmd_reader.data.repository.OfflineFirstWorkRepository
import com.example.kmd_reader.domain.model.IssueSource
import com.example.kmd_reader.domain.model.PresentationMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [28])
class CommunityApiIntegrationTest {
    private lateinit var database: KmdReaderDatabase
    private lateinit var repository: OfflineFirstWorkRepository

    @Before
    fun createRepository() {
        assumeTrue(
            "Set -Dkmd.integration=true and start kmd-community-api before running this test.",
            System.getProperty("kmd.integration") == "true"
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KmdReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = OfflineFirstWorkRepository(
            workDao = database.workDao(),
            issueDao = database.scriptIssueDao(),
            api = NetworkModule.createApi(
                baseUrl = System.getProperty("kmd.apiBaseUrl") ?: "http://127.0.0.1:3000/",
                enableLogging = false
            ),
            nowMillis = { 1_700_000_000_000L }
        )
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    @Test
    fun refreshWorksAndIssuesFromCommunityApi() = runTest {
        val works = repository.listWorks(refresh = true)
        val glassRail = requireNotNull(works.firstOrNull { it.id == "glass-rail" })

        assertEquals(PresentationMode.Stage, glassRail.presentation.mode)
        assertTrue(works.size >= 3)

        val issues = repository.listIssues(workId = "glass-rail", refresh = true)

        assertTrue(issues.isNotEmpty())
        assertTrue(issues.any { it.source == IssueSource.Performance })
        assertEquals(works.size, repository.listWorks(refresh = false).size)

        val source = repository.getWorkSource(workId = "glass-rail", refresh = true)
        assertTrue(requireNotNull(source).contains("Glass Rail"))
    }

    @Test
    fun submitReviewToCommunityApi() = runTest {
        val response = repository.submitReview(
            ReviewRequestDto(
                workId = "glass-rail",
                reviewerName = "android-integration",
                decision = "needs_changes",
                note = "Integration smoke test from Android Repository."
            )
        )

        assertTrue(response.accepted)
        assertEquals("glass-rail", response.workId)
        assertEquals("needs_changes", response.decision)
    }
}
