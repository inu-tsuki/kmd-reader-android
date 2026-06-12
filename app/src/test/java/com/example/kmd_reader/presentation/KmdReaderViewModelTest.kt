package com.example.kmd_reader.presentation

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.mock.MockKmdSources
import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderRuntimeCapabilities
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import com.example.kmd_reader.runtime.ReaderRuntimeTimelineMarker
import com.example.kmd_reader.runtime.ReaderSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        assertEquals(false, readerSession.isPlaying)
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

    @Test
    fun openReviewLoadsSourceSnapshot() = runTest {
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            nowMillis = { 42L }
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReview)

        val snapshot = viewModel.state.value.sourceSnapshotsByWorkId.getValue("glass-rail")
        assertEquals("glass-rail", snapshot.workId)
        assertEquals("rev-1", snapshot.revisionId)
        assertEquals(42L, snapshot.fetchedAtMillis)
        assertTrue(snapshot.content.contains("玻璃铁轨"))
        assertTrue(requireNotNull(snapshot.previewSnippet()).lines.isNotEmpty())
    }

    @Test
    fun readerControlsForwardToRuntimeAndUpdateState() = runTest {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        viewModel.onAction(KmdReaderAction.PlayReader)

        val playingSession = viewModel.state.value.readerSession
        assertTrue(playingSession is ReaderSessionState.Ready)
        assertEquals(true, (playingSession as ReaderSessionState.Ready).isPlaying)

        viewModel.onAction(KmdReaderAction.SeekReader(0.75f))
        val seekSession = viewModel.state.value.readerSession as ReaderSessionState.Ready
        assertEquals(0.75f, seekSession.progress, 0.001f)

        viewModel.onAction(KmdReaderAction.PauseReader)
        val pausedSession = viewModel.state.value.readerSession as ReaderSessionState.Ready
        assertEquals(false, pausedSession.isPlaying)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun runtimeProgressUpdatesCurrentPlaybackLine() = runTest {
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()

        runtimeBridge.emit(
            ReaderRuntimeEvent.Ready(
                workId = "glass-rail",
                durationMs = 2400,
                timelineMarkers = listOf(
                    ReaderRuntimeTimelineMarker(
                        id = "p1-m2",
                        timeMs = 840,
                        durationMs = 400,
                        line = 9,
                        content = "After the glass rail"
                    )
                )
            )
        )
        runtimeBridge.emit(
            ReaderRuntimeEvent.ProgressChanged(
                workId = "glass-rail",
                progress = 0.36f,
                positionPayload = "segment:0:line:9",
                timeMs = 840,
                durationMs = 2400,
                markerId = "p1-m2"
            )
        )
        advanceUntilIdle()

        val readerSession = viewModel.state.value.readerSession
        assertTrue(readerSession is ReaderSessionState.Ready)
        val ready = readerSession as ReaderSessionState.Ready
        assertEquals(9, ready.currentLine)
        assertEquals("p1-m2", ready.currentMarkerId)
        assertEquals("p1-m2", ready.timelineMarkers.single().id)
    }

    @Test
    fun openReaderFailsWhenSourceIsMissing() = runTest {
        val viewModel = KmdReaderViewModel(SourceMissingWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)

        val readerSession = viewModel.state.value.readerSession
        assertTrue(readerSession is ReaderSessionState.Failed)
        assertEquals("glass-rail", (readerSession as ReaderSessionState.Failed).workId)
        assertEquals(ReaderChromeMode.Error, viewModel.state.value.readerChrome.mode)
    }

    @Test
    fun sourceFailureIsNotOverwrittenByLateRuntimeTransportReady() = runTest {
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = SourceMissingWorkRepository(),
            runtimeBridge = runtimeBridge
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)

        assertTrue(viewModel.state.value.readerSession is ReaderSessionState.Failed)
        assertEquals(listOf("glass-rail"), runtimeBridge.preparedWorkIds)
        assertEquals(0, runtimeBridge.loadCalls)

        runtimeBridge.emit(
            ReaderRuntimeEvent.TransportReady(
                runtime = "kmd-reader-runtime-web",
                version = 1,
                sessionId = "late-session",
                capabilities = ReaderRuntimeCapabilities(protocolVersion = 1)
            )
        )

        val readerSession = viewModel.state.value.readerSession
        assertTrue(readerSession is ReaderSessionState.Failed)
        assertEquals("glass-rail", (readerSession as ReaderSessionState.Failed).workId)
        assertEquals(ReaderChromeMode.Error, viewModel.state.value.readerChrome.mode)
    }

    @Test
    fun retryReaderRuntimeRestartsHostAndReloadsCurrentWork() = runTest {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        val initialToken = viewModel.state.value.readerHostRestartToken

        viewModel.onAction(KmdReaderAction.RetryReaderRuntime)

        assertEquals(initialToken + 1, viewModel.state.value.readerHostRestartToken)
        val readerSession = viewModel.state.value.readerSession
        assertTrue(readerSession is ReaderSessionState.Ready)
        assertEquals("glass-rail", (readerSession as ReaderSessionState.Ready).workId)
    }

    @Test
    fun readerHostSizeUpdatesViewportState() = runTest {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.UpdateReaderHostSize(widthPx = 1080, heightPx = 2274))
        viewModel.onAction(KmdReaderAction.OpenReader)

        val viewport = viewModel.state.value.readerViewport
        assertEquals(1080, viewport.hostWidthPx)
        assertEquals(2274, viewport.hostHeightPx)
        assertEquals(1920, viewport.runtimeViewport.width)
        assertEquals(1080, viewport.runtimeViewport.height)
        assertEquals("16:9", viewport.aspectRatio)
        assertEquals(1920, viewport.sourceHints?.designWidth)
        assertEquals(1080, viewport.sourceHints?.designHeight)
        assertEquals(true, viewport.letterboxed)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun submitIssueDraftAddsLocalIssueAndKeepsPlaybackAnchor() = runTest {
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { 99L }
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(
            ReaderRuntimeEvent.Ready(
                workId = "glass-rail",
                durationMs = 2400,
                timelineMarkers = listOf(
                    ReaderRuntimeTimelineMarker(
                        id = "m3",
                        timeMs = 1200,
                        durationMs = 400,
                        progress = 0.5f,
                        line = 3,
                        content = "three"
                    )
                )
            )
        )
        runtimeBridge.emit(
            ReaderRuntimeEvent.ProgressChanged(
                workId = "glass-rail",
                progress = 0.5f,
                positionPayload = "line:3",
                timeMs = 1200,
                durationMs = 2400,
                line = 3,
                markerId = "m3"
            )
        )
        advanceUntilIdle()

        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("第三行表现需要确认"))
        viewModel.onAction(KmdReaderAction.SubmitIssueDraft)

        val issues = viewModel.state.value.issuesByWorkId.getValue("glass-rail")
        val issue = issues.first { it.id == "local-99" }
        assertEquals("第三行表现需要确认", issue.message)
        assertEquals("local-99", viewModel.state.value.issueFocus.selectedIssueId)
        assertEquals(0.5f, viewModel.state.value.issueFocus.playbackAnchorsByIssueId
            .getValue("local-99").progress ?: -1f, 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun jumpIssueToPlaybackSeeksRuntimeByTimelineMarker() = runTest {
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge
        )

        viewModel.onAction(KmdReaderAction.OpenWork("choice-room"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(
            ReaderRuntimeEvent.Ready(
                workId = "choice-room",
                durationMs = 10_000,
                timelineMarkers = listOf(
                    ReaderRuntimeTimelineMarker(
                        id = "line-42",
                        timeMs = 4_200,
                        durationMs = 300,
                        line = 42,
                        content = "choice"
                    )
                )
            )
        )
        advanceUntilIdle()

        viewModel.onAction(KmdReaderAction.JumpIssueToPlayback("issue-choice-1"))
        advanceUntilIdle()

        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
        assertEquals("issue-choice-1", viewModel.state.value.issueFocus.selectedIssueId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun jumpSelectedSourceLineSeeksNextPlayableMarker() = runTest {
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(
            ReaderRuntimeEvent.Ready(
                workId = "glass-rail",
                durationMs = 10_000,
                timelineMarkers = listOf(
                    ReaderRuntimeTimelineMarker(
                        id = "line-8",
                        timeMs = 2_000,
                        durationMs = 300,
                        line = 8,
                        content = "before"
                    ),
                    ReaderRuntimeTimelineMarker(
                        id = "line-12",
                        timeMs = 4_200,
                        durationMs = 300,
                        line = 12,
                        content = "after"
                    )
                )
            )
        )
        advanceUntilIdle()

        viewModel.onAction(KmdReaderAction.SelectSourceLine(9))
        viewModel.onAction(KmdReaderAction.JumpSelectedSourceLineToPlayback)
        advanceUntilIdle()

        assertEquals(9, viewModel.state.value.issueFocus.selectedSourceLine)
        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
    }
}

private class FakeWorkRepository : WorkRepository {
    override suspend fun listWorks(refresh: Boolean): List<Work> = MockWorks.works

    override suspend fun getWork(id: String, refresh: Boolean): Work? =
        MockWorks.works.firstOrNull { it.id == id }

    override suspend fun listIssues(workId: String, refresh: Boolean): List<ScriptIssue> =
        MockWorks.issues[workId].orEmpty()

    override suspend fun getWorkSource(workId: String, refresh: Boolean): String? =
        MockKmdSources.sourceFor(workId)
}

private class SourceMissingWorkRepository : WorkRepository {
    override suspend fun listWorks(refresh: Boolean): List<Work> = MockWorks.works

    override suspend fun getWork(id: String, refresh: Boolean): Work? =
        MockWorks.works.firstOrNull { it.id == id }

    override suspend fun listIssues(workId: String, refresh: Boolean): List<ScriptIssue> =
        emptyList()
}

private class ManualRuntimeBridge : ReaderRuntimeBridge {
    private val mutableEvents = MutableSharedFlow<ReaderRuntimeEvent>(extraBufferCapacity = 16)
    override val events: Flow<ReaderRuntimeEvent> = mutableEvents.asSharedFlow()

    val preparedWorkIds = mutableListOf<String>()
    val seekCalls = mutableListOf<Float>()
    var loadCalls: Int = 0
        private set

    override suspend fun attach() = Unit

    override fun prepareLoad(workId: String) {
        preparedWorkIds += workId
    }

    override suspend fun load(request: ReaderLoadRequest) {
        loadCalls += 1
    }

    override suspend fun play() = Unit

    override suspend fun pause() = Unit

    override suspend fun seek(progress: Float) {
        seekCalls += progress
    }

    override suspend fun setInspectionEnabled(enabled: Boolean) = Unit

    override suspend fun updateSettings(settings: ReaderSettings) = Unit

    override fun dispose() = Unit

    suspend fun emit(event: ReaderRuntimeEvent) {
        mutableEvents.emit(event)
    }
}
