package com.example.kmd_reader.presentation

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KmdReaderViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initLoadsWorksFromRepository() = runTest {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        assertEquals(MockWorks.works.size, viewModel.state.value.works.size)
        assertEquals(false, viewModel.state.value.isLoadingWorks)
    }

    @Test
    fun openWorkLoadsIssuesIntoState() = runTest {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))

        assertEquals("glass-rail", viewModel.state.value.deskStack.currentWorkId)
        assertTrue(viewModel.state.value.issuesByWorkId.getValue("glass-rail").isNotEmpty())
    }

    @Test
    fun openReaderCreatesReadyReaderSession() = runTest {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)

        val readerSession = viewModel.state.value.readerSession
        assertTrue(readerSession is ReaderSessionState.Ready)
        assertEquals("glass-rail", (readerSession as ReaderSessionState.Ready).workId)
        assertEquals(0.42f, readerSession.progress, 0.001f)
    }

    @Test
    fun openReviewAfterReaderMergesRuntimeInspectionIssues() = runTest {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        viewModel.onAction(KmdReaderAction.OpenReview)

        val issues = viewModel.state.value.issuesByWorkId.getValue("glass-rail")
        assertTrue(issues.any { it.id == "runtime-glass-rail-inspection" })
    }
}

private class FakeWorkRepository : WorkRepository {
    override suspend fun listWorks(refresh: Boolean): List<Work> = MockWorks.works

    override suspend fun getWork(id: String, refresh: Boolean): Work? =
        MockWorks.works.firstOrNull { it.id == id }

    override suspend fun listIssues(workId: String, refresh: Boolean): List<ScriptIssue> =
        MockWorks.issues[workId].orEmpty()
}
