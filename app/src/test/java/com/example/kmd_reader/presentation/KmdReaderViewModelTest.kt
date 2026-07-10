package com.example.kmd_reader.presentation

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.mock.MockKmdSources
import com.example.kmd_reader.data.repository.InMemoryLocalLibraryRepository
import com.example.kmd_reader.data.repository.LocalDraft
import com.example.kmd_reader.data.repository.LocalDraftTypes
import com.example.kmd_reader.data.repository.LocalLibraryEntry
import com.example.kmd_reader.data.repository.LocalLibraryRepository
import com.example.kmd_reader.data.repository.LocalRevision
import com.example.kmd_reader.data.mock.MockWorks
import com.example.kmd_reader.domain.model.IssueSeverity
import com.example.kmd_reader.domain.model.KmdSourceRange
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.domain.model.WorkSourceType
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderRuntimeCapabilities
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import com.example.kmd_reader.runtime.ReaderRuntimeTimelineMarker
import com.example.kmd_reader.runtime.ReaderSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── R3-B 进度持久化 ──

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openReaderAutoCreatesLibraryEntryOnShelfFalse() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = ManualRuntimeBridge(),
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()

        val entry = localLibrary.getEntry("glass-rail")
        assertEquals("glass-rail", entry?.workId)
        assertEquals(false, entry?.onShelf)
        assertEquals(0f, entry?.readingProgress ?: -1f, 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun readyRestoresSavedSeekProgress() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(progressEntry("glass-rail", progress = 0.42f, durationMs = 2400))
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(
            ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400)
        )
        advanceUntilIdle()

        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun readyRestoresSeekDespiteDurationMismatch() = runTest {
        // F4：duration 不匹配时仍恢复 seek（按比例定位，不依赖 duration 基准）。
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(progressEntry("glass-rail", progress = 0.42f, durationMs = 2400))
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(
            ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 4800)
        )
        advanceUntilIdle()

        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun progressChangedThrottledWrites() = runTest {
        // 可控时钟：每次调用自增，模拟时间推进。
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        // 首个 ProgressChanged（clock=0）应触发首次落盘（0 - 0 >= 0）。
        clock = 0L
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.1f, timeMs = 240, durationMs = 2400))
        advanceUntilIdle()
        assertEquals(0.1f, localLibrary.getEntry("glass-rail")?.readingProgress ?: -1f, 0.001f)

        // 间隔不足 5s 的后续事件应被节流，进度不更新。
        clock = 3_000L
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.2f, timeMs = 480, durationMs = 2400))
        advanceUntilIdle()
        assertEquals(
            "throttled: progress must not be overwritten within interval",
            0.1f,
            localLibrary.getEntry("glass-rail")?.readingProgress ?: -1f,
            0.001f
        )

        // 推进 ≥5s 后的事件应写入。
        clock = 5_001L
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.3f, timeMs = 720, durationMs = 2400))
        advanceUntilIdle()
        assertEquals(0.3f, localLibrary.getEntry("glass-rail")?.readingProgress ?: -1f, 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun progressChangedPersistsProgressFields() = runTest {
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.5f, timeMs = 1200, durationMs = 2400))
        advanceUntilIdle()

        val entry = localLibrary.getEntry("glass-rail")
        assertEquals(0.5f, entry?.readingProgress ?: -1f, 0.001f)
        assertEquals(1200L, entry?.readingTimeMs)
        assertEquals(2400L, entry?.readingDurationMs)
        assertEquals(0L, entry?.lastReadAt)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun onClearedFlushesLatestProgress() = runTest {
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()
        // 首个事件触发首次落盘后，节流窗口内的后续事件不应落盘……
        clock = 1_000L
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.9f, timeMs = 2160, durationMs = 2400))
        advanceUntilIdle()
        // ……但 onCleared 必须兜底写入最新进度。
        // 直接调 flushProgressOnCleared（onCleared 是 protected，测试不可见；
        // 抽出的 internal 方法承载兜底落盘逻辑，onCleared 也调它）。
        clock = 2_000L
        viewModel.flushProgressOnCleared()

        val entry = localLibrary.getEntry("glass-rail")
        assertEquals(
            "onCleared must flush the latest progress regardless of throttle window",
            0.9f,
            entry?.readingProgress ?: -1f,
            0.001f
        )
        assertEquals(2_000L, entry?.lastReadAt)
    }

    // ===== PR #5 审阅修复回归 =====

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun staleReadyEventDoesNotMutateSessionWhenDeskStackMovedOn() = runTest {
        // F1：切到 work B 后，work A 迟到的 Ready 不能把 session 改回 A。
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = InMemoryLocalLibraryRepository()
        )

        // 加载 work A 到 Ready，再切到 work B。
        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        viewModel.onAction(KmdReaderAction.OpenWork("rain-city"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()

        // 迟到的 A.Ready 到达（deskStack.currentWorkId 已是 B）。
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        val session = viewModel.state.value.readerSession
        assertTrue(
            "stale A.Ready must not flip session back to A; expected B-related, got $session",
            session !is ReaderSessionState.Ready || session.workId == "rain-city"
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun staleProgressEventDoesNotPersistWhenDeskStackMovedOn() = runTest {
        // F1：切到 work B 后，work A 迟到的 ProgressChanged 不能写脏 B 的进度。
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        // work A Ready，落一笔正常进度。
        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.2f, timeMs = 480, durationMs = 2400))
        advanceUntilIdle()

        // 切到 work B（首次打开，entry 初值 progress=0）。
        viewModel.onAction(KmdReaderAction.OpenWork("rain-city"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "rain-city", durationMs = 3000))
        advanceUntilIdle()

        // 迟到的 A.ProgressChanged(0.99) 到达——绝不能写进 B 的 entry。
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.99f, timeMs = 2376, durationMs = 2400))
        advanceUntilIdle()

        val rainEntry = localLibrary.getEntry("rain-city")
        assertTrue(
            "stale A progress must not poison B's entry; got ${rainEntry?.readingProgress}",
            rainEntry == null || rainEntry.readingProgress < 0.9f
        )
        // A 的 entry 也不应被这次迟到事件改写（仍是 0.2）。
        assertEquals(0.2f, localLibrary.getEntry("glass-rail")?.readingProgress ?: -1f, 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun restoreSeekThenFlushBeforeProgressEchoPersistsRestoredProgress() = runTest {
        // F2：Ready→restore(0.42)→立即 flush（runtime 还没 echo ProgressChanged），
        // 必须写入恢复点 0.42，而非 Ready 设的 0f。
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(progressEntry("glass-rail", progress = 0.42f, durationMs = 2400))
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        // restore seek 已执行（seekCalls=0.42），此时不发任何 ProgressChanged，直接 flush。
        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
        viewModel.flushProgressOnCleared()

        val entry = localLibrary.getEntry("glass-rail")
        assertEquals(
            "flush before progress echo must persist restored progress, not 0f",
            0.42f,
            entry?.readingProgress ?: -1f,
            0.001f
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun throttleResetsAcrossWorks() = runTest {
        // F3：work A 刚落盘（节流窗口内），立即开 work B，B 的首个进度事件不应被 A 的窗口误杀。
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )

        // work A Ready + 首个进度落盘（clock=0，触发首次写）。
        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.1f, timeMs = 240, durationMs = 2400))
        advanceUntilIdle()
        assertEquals(0.1f, localLibrary.getEntry("glass-rail")?.readingProgress ?: -1f, 0.001f)

        // 1 秒后切到 work B（仍在 A 的 5s 节流窗口内）。
        clock = 1_000L
        viewModel.onAction(KmdReaderAction.OpenWork("rain-city"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "rain-city", durationMs = 3000))
        advanceUntilIdle()

        // B 的首个进度事件——必须写入，不被 A 的节流窗口误杀。
        clock = 1_001L
        runtimeBridge.emit(progressEvent("rain-city", progress = 0.3f, timeMs = 900, durationMs = 3000))
        advanceUntilIdle()
        assertEquals(
            "per-work throttle must let B's first event through despite A's window",
            0.3f,
            localLibrary.getEntry("rain-city")?.readingProgress ?: -1f,
            0.001f
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun readyRestoresSeekWhenSavedDurationIsNull() = runTest {
        // F4：entry.readingDurationMs=null 时仍恢复 seek——progress 是比例值，
        // 不需要 duration 基准。原测试断言不恢复（OQ 严格语义），现在断言恢复。
        val localLibrary = InMemoryLocalLibraryRepository()
        // durationMs=null 模拟旧 entry。
        val work = MockWorks.works.first { it.id == "glass-rail" }
        localLibrary.upsertEntry(
            LocalLibraryEntry(
                workId = "glass-rail",
                source = work.sourceType,
                onShelf = false,
                title = work.title,
                authorName = work.authorName,
                presentationMode = work.presentation.mode,
                aspectRatio = work.presentation.aspectRatio,
                kmdSource = null,
                contentUri = work.contentUri,
                readingProgress = 0.42f,
                readingTimeMs = null,
                readingDurationMs = null,
                lastReadAt = null,
                importedAt = null,
                cachedAt = null
            )
        )
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun readyRestoresSeekWhenEventDurationIsNull() = runTest {
        // F4：event.durationMs=null 时仍恢复 seek——progress 是比例值，不需要 duration 基准。
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(progressEntry("glass-rail", progress = 0.42f, durationMs = 2400))
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        // event 不带 durationMs。
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = null))
        advanceUntilIdle()

        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun readyDoesNotRestoreSeekWhenRevisionChanged() = runTest {
        // F4-rev（审查 High）：entry 持久化的 activeRevisionId="rev-2"（上次存进度时
        // 播放的版本），当前播放版本是 "rev-1"（MockWorks 默认）→ revision 变更，
        // 同一百分比可能落在完全不同的叙事位置 → 不 seek。
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(progressEntry("glass-rail", progress = 0.42f, durationMs = 2400, activeRevisionId = "rev-2"))
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        // loadCurrentReaderWork 构建 snapshot with revisionId="rev-1"（MockWorks 默认）
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        assertTrue(
            "revision changed must skip restore seek",
            runtimeBridge.seekCalls.isEmpty()
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun readyRestoresSeekWhenRevisionMatches() = runTest {
        // F4-rev：entry activeRevisionId="rev-1" 与当前播放版本一致 → 恢复正常。
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(progressEntry("glass-rail", progress = 0.42f, durationMs = 2400, activeRevisionId = "rev-1"))
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun readyRestoresSeekWhenSavedRevisionIsNull() = runTest {
        // F4-rev 向后兼容：entry.activeRevisionId=null（旧 entry 无 revision 记录），
        // 当前播放版本非 null → 仍恢复（无身份可比，宽容）。
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(progressEntry("glass-rail", progress = 0.42f, durationMs = 2400, activeRevisionId = null))
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        assertEquals(0.42f, runtimeBridge.seekCalls.single(), 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueReadingRestoresProgressAfterProgressOnlySave() = runTest {
        // F4 端到端回归：模拟 runtime 不带 duration 的完整 save-then-restore 周期。
        // ProgressChanged(durationMs=null) → flush → 重建 VM → Ready(durationMs=null) → 仍 seek。
        // Bug A（updateProgress null 覆盖 readingDurationMs）+ Bug B（duration 硬门控）联合症状：
        // 原实现在此场景下永远跳过恢复。
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        // 首次阅读：建 entry（带初始 duration 2400）
        localLibrary.upsertEntry(progressEntry("glass-rail", progress = 0f, durationMs = 2400))
        val runtimeBridge1 = ManualRuntimeBridge()
        val viewModel1 = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge1,
            localLibrary = localLibrary,
            nowMillis = { clock }
        )
        viewModel1.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel1.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge1.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = null))
        advanceUntilIdle()
        // runtime 发 ProgressChanged 不带 duration（模拟实际 runtime 行为）
        runtimeBridge1.emit(
            ReaderRuntimeEvent.ProgressChanged(
                workId = "glass-rail",
                progress = 0.5f,
                positionPayload = "line:0",
                timeMs = 1200,
                durationMs = null
            )
        )
        advanceUntilIdle()
        // flush（模拟 onCleared）
        viewModel1.flushProgressOnCleared()

        // 验证 DB 中的 duration 未被 null 覆盖（Bug A）
        val entry = localLibrary.getEntry("glass-rail")
        assertEquals(0.5f, entry?.readingProgress ?: -1f, 0.001f)
        assertEquals(2400L, entry?.readingDurationMs)

        // 重建 VM（模拟下次打开）
        clock = 5000L
        val runtimeBridge2 = ManualRuntimeBridge()
        val viewModel2 = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge2,
            localLibrary = localLibrary,
            nowMillis = { clock }
        )
        viewModel2.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel2.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge2.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = null))
        advanceUntilIdle()

        assertEquals(0.5f, runtimeBridge2.seekCalls.single(), 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updateProgressPreservesExistingDurationWhenIncomingIsNull() = runTest {
        // F4 单元测试：updateProgress(durationMs=null) 不应清除已有的 readingDurationMs。
        // F4-rev：同时验证 revisionId=null 不清除已有的 activeRevisionId。
        val localLibrary = InMemoryLocalLibraryRepository()
        // 初始 entry 带 durationMs=2400, activeRevisionId="rev-1"
        localLibrary.upsertEntry(
            progressEntry("glass-rail", progress = 0.3f, durationMs = 2400, activeRevisionId = "rev-1")
        )
        // 第一次 updateProgress 带 durationMs=2400, revisionId="rev-1"（正常）
        localLibrary.updateProgress("glass-rail", 0.5f, 1200, 2400, 1000, "rev-1")
        assertEquals(2400L, localLibrary.getEntry("glass-rail")?.readingDurationMs)
        assertEquals("rev-1", localLibrary.getEntry("glass-rail")?.activeRevisionId)
        // 第二次 updateProgress 带 durationMs=null, revisionId=null（runtime 未上报）→ 不应覆盖
        localLibrary.updateProgress("glass-rail", 0.6f, 1440, null, 2000, null)
        assertEquals(
            "updateProgress must preserve existing readingDurationMs when incoming is null",
            2400L,
            localLibrary.getEntry("glass-rail")?.readingDurationMs
        )
        assertEquals(
            "updateProgress must preserve existing activeRevisionId when incoming is null",
            "rev-1",
            localLibrary.getEntry("glass-rail")?.activeRevisionId
        )
        assertEquals(0.6f, localLibrary.getEntry("glass-rail")?.readingProgress ?: -1f, 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stalePlaybackStateChangedDoesNotFlushZeroOverSavedProgress() = runTest {
        // 复核 Remaining Finding：切到 B 后，A 迟到的 PlaybackStateChanged 不能把 session
        // 改回 A（progress 退成 0f），否则随后 onCleared 会用 0f 覆盖 A 的真实进度。
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        // work A Ready，落一笔进度 0.5。
        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.5f, timeMs = 1200, durationMs = 2400))
        advanceUntilIdle()
        assertEquals(0.5f, localLibrary.getEntry("glass-rail")?.readingProgress ?: -1f, 0.001f)

        // 切到 work B。
        viewModel.onAction(KmdReaderAction.OpenWork("rain-city"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "rain-city", durationMs = 3000))
        advanceUntilIdle()

        // 迟到的 A.PlaybackStateChanged 到达——必须被门控拦截，不能改 session。
        runtimeBridge.emit(
            ReaderRuntimeEvent.PlaybackStateChanged(workId = "glass-rail", isPlaying = true, state = "playing")
        )
        advanceUntilIdle()

        val session = viewModel.state.value.readerSession
        assertTrue(
            "stale A.PlaybackStateChanged must not flip session to A; got $session",
            session !is ReaderSessionState.Ready || session.workId == "rain-city"
        )

        // onCleared 兜底落盘：A 的进度绝不能被写成 0f。
        viewModel.flushProgressOnCleared()
        assertEquals(
            "flush must not overwrite A's progress with 0f from a stale playback event",
            0.5f,
            localLibrary.getEntry("glass-rail")?.readingProgress ?: -1f,
            0.001f
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun staleFailedEventDoesNotOverwriteCurrentSession() = runTest {
        // 复核 Remaining Finding：切到 B 后，A 迟到的 Failed 不能把当前 B 的 session
        // 切成 Failed(A)，否则 B 的最后一笔进度无法经 onCleared 兜底落盘。
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        // work A Ready。
        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        // 切到 work B，B Ready 并播放到 0.6。
        viewModel.onAction(KmdReaderAction.OpenWork("rain-city"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "rain-city", durationMs = 3000))
        advanceUntilIdle()
        runtimeBridge.emit(progressEvent("rain-city", progress = 0.6f, timeMs = 1800, durationMs = 3000))
        advanceUntilIdle()

        // 迟到的 A.Failed 到达——必须被门控拦截，不能把 B 的 session 切成 Failed(A)。
        runtimeBridge.emit(
            ReaderRuntimeEvent.Failed(workId = "glass-rail", message = "stale failure", recoverable = false)
        )
        advanceUntilIdle()

        val session = viewModel.state.value.readerSession
        assertFalse(
            "stale A.Failed must not overwrite current B session; got $session",
            session is ReaderSessionState.Failed && session.workId == "glass-rail"
        )
        // B 的 session 应仍是 Ready，使 onCleared 能兜底落盘 0.6。
        assertTrue("B session must remain Ready for flush", session is ReaderSessionState.Ready)
        viewModel.flushProgressOnCleared()
        assertEquals(
            "B's latest progress must still flush after a stale A failure",
            0.6f,
            localLibrary.getEntry("rain-city")?.readingProgress ?: -1f,
            0.001f
        )
    }

    // ===== R3-C：issue 草稿本地缓冲 =====

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updateIssueDraftMessageDebouncesWrites() = runTest {
        // R3-C 步骤2：时钟节流。clock=0 首次存，clock=100 窗口内跳过，clock=1001 窗口外再存。
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")

        // 起草（生成 draft id），首次按键 clock=0 触发存盘。
        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("a"))
        advanceUntilIdle()
        val draftId = viewModel.state.value.issueFocus.issueDraft?.id
        assertTrue("draft id should be assigned", draftId.orEmpty().isNotBlank())
        val saved0 = localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE)
        assertEquals(1, saved0.size)
        assertTrue(saved0.first().payload.contains(""""message":"a""""))

        // clock=100 窗口内——跳过。
        clock = 100L
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("ab"))
        advanceUntilIdle()
        val saved1 = localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE)
        assertEquals("windowed keystroke must not write", "a", saved1.first().let { issueDraftFromJson(it.payload).message })

        // clock=1001 窗口外——存。
        clock = 1_001L
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("abc"))
        advanceUntilIdle()
        val saved2 = localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE)
        assertEquals("post-window keystroke must write", "abc", saved2.first().let { issueDraftFromJson(it.payload).message })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startIssueDraftRestoresPersistedMessage() = runTest {
        // R3-C 步骤3：StartIssueDraft 时恢复未提交草稿的 message/suggestion/severity。
        val localLibrary = InMemoryLocalLibraryRepository()
        // 预置一条未提交草稿。
        val preDraft = IssueDraft(
            id = "draft-old",
            workId = "glass-rail",
            revisionId = "rev-1",
            message = "之前写到一半的内容",
            suggestion = "之前的修改建议",
            severity = IssueSeverity.Error
        )
        localLibrary.saveDraft(
            LocalDraft("draft-old", "glass-rail", LocalDraftTypes.ISSUE, preDraft.toJson(), 0)
        )
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")

        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()

        val draft = viewModel.state.value.issueFocus.issueDraft
        assertNotNull("draft should be created", draft)
        assertEquals("restored message", "之前写到一半的内容", draft!!.message)
        assertEquals("restored suggestion", "之前的修改建议", draft.suggestion)
        assertEquals("restored severity", IssueSeverity.Error, draft.severity)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startIssueDraftWithNoPersistedDraftUsesDefaults() = runTest {
        // R3-C 步骤3：无持久化草稿时用 reducer 默认值。
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")

        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()

        val draft = viewModel.state.value.issueFocus.issueDraft
        assertNotNull("draft should be created", draft)
        // reducer 默认 suggestion。
        assertEquals("请补充期望表现或修改建议。", draft!!.suggestion)
        assertTrue("no persisted draft → empty local_drafts", localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun submitIssueDraftDeletesPersistedDraft() = runTest {
        // R3-C 步骤4：提交后草稿从 local_drafts 删除。
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { 42L },
            localLibrary = localLibrary
        )
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")

        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("待确认的问题"))
        advanceUntilIdle()
        assertFalse("draft should be persisted before submit", localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).isEmpty())

        viewModel.onAction(KmdReaderAction.SubmitIssueDraft)
        advanceUntilIdle()

        assertTrue(
            "draft must be deleted after submit",
            localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).isEmpty()
        )
        // issue 已创建。
        val issues = viewModel.state.value.issuesByWorkId.getValue("glass-rail")
        assertTrue(issues.any { it.message == "待确认的问题" })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelIssueDraftDeletesPersistedDraft() = runTest {
        // R3-C 步骤4：取消后草稿从 local_drafts 删除。
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")

        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("写到一半"))
        advanceUntilIdle()
        assertFalse(localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).isEmpty())

        viewModel.onAction(KmdReaderAction.CancelIssueDraft)
        advanceUntilIdle()

        assertTrue(
            "draft must be deleted after cancel",
            localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).isEmpty()
        )
        assertNull("issueDraft cleared", viewModel.state.value.issueFocus.issueDraft)
    }

    @Test
    fun draftSerializeRoundTripPreservesFields() {
        // R3-C 步骤1：IssueDraft ↔ JSON 字段无损。
        val original = IssueDraft(
            id = "draft-x",
            workId = "glass-rail",
            revisionId = "rev-1",
            sourceRange = KmdSourceRange(startLine = 3, endLine = 5),
            playbackAnchor = PlaybackAnchor(timeMs = 1200, progress = 0.5f, line = 3, markerId = "m3"),
            severity = IssueSeverity.Warning,
            message = "这条表现需要确认",
            suggestion = "建议调整效果"
        )
        val json = original.toJson()
        val restored = issueDraftFromJson(json)
        assertEquals(original.id, restored.id)
        assertEquals(original.workId, restored.workId)
        assertEquals(original.revisionId, restored.revisionId)
        assertEquals(original.sourceRange, restored.sourceRange)
        assertEquals(original.playbackAnchor, restored.playbackAnchor)
        assertEquals(original.severity, restored.severity)
        assertEquals(original.message, restored.message)
        assertEquals(original.suggestion, restored.suggestion)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun restoredDraftSubmitDeletesCorrectRowNotOrphan() = runTest {
        // F1：预置 draft-old，StartIssueDraft 恢复它，submit 后 draft-old 行必须被删除
        // （修复前会生成新 UUID → 删错行 → draft-old 留为孤儿 → 下次又恢复）。
        val localLibrary = InMemoryLocalLibraryRepository()
        val preDraft = IssueDraft(
            id = "draft-old",
            workId = "glass-rail",
            revisionId = "rev-1",
            message = "之前写到一半",
            suggestion = "建议",
            severity = IssueSeverity.Warning
        )
        localLibrary.saveDraft(
            LocalDraft("draft-old", "glass-rail", LocalDraftTypes.ISSUE, preDraft.toJson(), 0)
        )
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { 100L },
            localLibrary = localLibrary
        )
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")

        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()

        // 恢复后 IssueDraft.id 应等于 draft-old（复用持久化 id）。
        val draft = viewModel.state.value.issueFocus.issueDraft
        assertNotNull(draft)
        assertEquals("draft-old", draft!!.id)

        viewModel.onAction(KmdReaderAction.SubmitIssueDraft)
        advanceUntilIdle()

        assertTrue(
            "draft-old row must be gone after submit (no orphan)",
            localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).isEmpty()
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun trailingFlushPersistsLastEditBeforeNavigation() = runTest {
        // F2：用户在节流窗口内打完最后一字后切走（SelectSourceLine），
        // 最后一笔必须落盘（修复前 leading-edge throttle 会跳过，最后一字丢失）。
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")

        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()

        // clock=0 首次存。
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("hello"))
        advanceUntilIdle()
        assertEquals("hello", issueDraftFromJson(localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).first().payload).message)

        // clock=100 窗口内——跳过存盘。
        clock = 100L
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("hello world"))
        advanceUntilIdle()
        assertEquals(
            "windowed edit not yet saved",
            "hello",
            issueDraftFromJson(localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).first().payload).message
        )

        // 切走（SelectSourceLine）——trailing flush 必须存最后一笔 "hello world"。
        viewModel.onAction(KmdReaderAction.SelectSourceLine(5))
        advanceUntilIdle()
        assertEquals(
            "trailing flush must persist the last edit before navigation",
            "hello world",
            issueDraftFromJson(localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).first().payload).message
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun staleRestoreDoesNotOverwriteDraftAfterWorkSwitch() = runTest {
        // F1：work A 起草后切到 work B 起草，A 的迟到恢复结果不能覆盖 B 的草稿。
        //
        // 真正的竞态窗口：A 的 getDraftsByType 必须在 B 起草完成后才返回。
        // 早期版本在 A 起草后立刻 advanceUntilIdle()，让 A 的恢复提前完成，并没有
        // 制造“迟到 A 覆盖 B”的竞态——用 CompletableDeferred 卡住 A 的查询，
        // 在 B 起草完成后再释放，精确复现迟到路径，验证 requestedWorkId guard。
        val gateForA = CompletableDeferred<List<LocalDraft>>()
        val localLibrary = ControllableLocalLibraryRepository(firstGetDraftsByTypeGate = gateForA)

        // 预置 A 的旧草稿（A 查询释放后会返回它）。
        val preDraftA = IssueDraft(
            id = "draft-A", workId = "glass-rail", revisionId = "rev-1",
            message = "A的内容", severity = IssueSeverity.Warning
        )
        localLibrary.saveDraft(
            LocalDraft("draft-A", "glass-rail", LocalDraftTypes.ISSUE, preDraftA.toJson(), 0)
        )
        // 预置 B 的旧草稿。
        val preDraftB = IssueDraft(
            id = "draft-B", workId = "rain-city", revisionId = "rev-2",
            message = "B的内容", severity = IssueSeverity.Warning
        )
        localLibrary.saveDraft(
            LocalDraft("draft-B", "rain-city", LocalDraftTypes.ISSUE, preDraftB.toJson(), 0)
        )

        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            localLibrary = localLibrary
        )

        // work A Ready，起草 A（触发异步 getDraftsByType("glass-rail")，卡在 gate 上）。
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")
        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()
        // A 的恢复协程正挂在 gateForA.await()，没有完成。

        // 切到 work B Ready，起草 B（B 的查询走默认路径，立即完成并回填 B）。
        viewModel.onAction(KmdReaderAction.OpenWork("rain-city"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "rain-city", durationMs = 3000))
        advanceUntilIdle()
        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()
        // B 的恢复已完成，当前草稿是 B。

        // 现在释放 A 的迟到查询——requestedWorkId guard 必须丢弃这笔回填。
        gateForA.complete(
            listOf(
                LocalDraft("draft-A", "glass-rail", LocalDraftTypes.ISSUE, preDraftA.toJson(), 0)
            )
        )
        advanceUntilIdle()

        // 当前草稿必须是 B 的（message="B的内容"），不能被 A 的迟到恢复覆盖。
        val draft = viewModel.state.value.issueFocus.issueDraft
        assertNotNull(draft)
        assertEquals(
            "stale A restore must not overwrite B's draft",
            "rain-city",
            draft!!.workId
        )
        assertEquals(
            "B's restored message must survive late A restore",
            "B的内容",
            draft.message
        )
        // 关键回归点：B 的 id（draft-B）绝不能被 A 的迟到回填改成 draft-A。
        assertEquals("draft-B", draft.id)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openWorkFlushesPendingDraftBeforeReplacingIssueFocus() = runTest {
        // F2（续）：用户在节流窗口内打完最后一字后直接 OpenWork 切到另一本作品，
        // 最后一笔必须落盘（修复前 OpenWork 未纳入 trailing flush，会丢）。
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )
        bringReaderToReady(viewModel, runtimeBridge, "glass-rail")

        viewModel.onAction(KmdReaderAction.StartIssueDraftFromPlayback)
        advanceUntilIdle()

        // clock=0 首次存。
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("first edit"))
        advanceUntilIdle()
        assertEquals(
            "first edit",
            issueDraftFromJson(localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).first().payload).message
        )

        // clock=100 窗口内——跳过存盘。
        clock = 100L
        viewModel.onAction(KmdReaderAction.UpdateIssueDraftMessage("second edit"))
        advanceUntilIdle()
        assertEquals(
            "windowed edit not yet saved",
            "first edit",
            issueDraftFromJson(localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).first().payload).message
        )

        // OpenWork 切到另一本——trailing flush 必须存最后一笔 "second edit"。
        viewModel.onAction(KmdReaderAction.OpenWork("rain-city"))
        advanceUntilIdle()
        assertEquals(
            "OpenWork must flush pending draft before replacing issue focus",
            "second edit",
            issueDraftFromJson(localLibrary.getDraftsByType("glass-rail", LocalDraftTypes.ISSUE).first().payload).message
        )
    }

    // ── R3-F 书架 UI ──

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshShelfPopulatesShelfFromOnShelfEntries() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        localLibrary.upsertEntry(
            shelfEntry("local-kmd-1", onShelf = true, importedAt = 1000L, lastReadAt = 2000L)
        )
        localLibrary.upsertEntry(
            shelfEntry("local-kmd-2", onShelf = true, importedAt = 3000L, lastReadAt = null)
        )
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        val shelf = viewModel.state.value.shelfState.shelf
        assertEquals(2, shelf.size)
        // getShelf 按 COALESCE(lastReadAt, importedAt) DESC——local-kmd-1 有 lastReadAt=2000 > local-kmd-2 importedAt=3000?
        // InMemory 排序: sortedByDescending { lastReadAt ?: importedAt ?: 0 }
        // local-kmd-1: 2000, local-kmd-2: 3000 → local-kmd-2 先
        assertEquals("local-kmd-2", shelf[0].workId)
        assertEquals("local-kmd-1", shelf[1].workId)
        assertTrue(shelf.all { it.onShelf })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshShelfPopulatesHistoryFromLastReadAtEntries() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // onShelf=false 但有 lastReadAt → 历史
        localLibrary.upsertEntry(
            shelfEntry("remote-1", onShelf = false, importedAt = null, lastReadAt = 5000L)
        )
        localLibrary.upsertEntry(
            shelfEntry("remote-2", onShelf = false, importedAt = null, lastReadAt = 3000L)
        )
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        val history = viewModel.state.value.shelfState.history
        assertEquals(2, history.size)
        // 按 lastReadAt DESC
        assertEquals("remote-1", history[0].workId)
        assertEquals("remote-2", history[1].workId)
        assertTrue(history.all { !it.onShelf })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshShelfExcludesEntriesWithoutShelfOrHistory() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // onShelf=false + lastReadAt=null → 既不在书架也不在历史
        localLibrary.upsertEntry(
            shelfEntry("phantom", onShelf = false, importedAt = null, lastReadAt = null)
        )
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        val shelfState = viewModel.state.value.shelfState
        assertTrue(shelfState.shelf.isEmpty())
        assertTrue(shelfState.history.isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshShelfSeparatesShelfAndHistoryWhenEntryIsOnBoth() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // onShelf=true + lastReadAt!=null → 在书架里，但历史列表应排除 onShelf=true 的
        localLibrary.upsertEntry(
            shelfEntry("dual", onShelf = true, importedAt = 1000L, lastReadAt = 5000L)
        )
        // onShelf=false + lastReadAt!=null → 只在历史
        localLibrary.upsertEntry(
            shelfEntry("history-only", onShelf = false, importedAt = null, lastReadAt = 3000L)
        )
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        val shelfState = viewModel.state.value.shelfState
        // 书架只有 onShelf=true 的
        assertEquals(1, shelfState.shelf.size)
        assertEquals("dual", shelfState.shelf[0].workId)
        // 历史只有 onShelf=false 且 lastReadAt!=null 的
        assertEquals(1, shelfState.history.size)
        assertEquals("history-only", shelfState.history[0].workId)
    }

    @Test
    fun openSettingsSetsIsSettingsOpen() {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenSettings)

        assertEquals(true, viewModel.state.value.deskStack.isSettingsOpen)
    }

    @Test
    fun closeSettingsClearsIsSettingsOpen() {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenSettings)
        assertEquals(true, viewModel.state.value.deskStack.isSettingsOpen)

        viewModel.onAction(KmdReaderAction.CloseSettings)
        assertEquals(false, viewModel.state.value.deskStack.isSettingsOpen)
    }

    @Test
    fun openSettingsClosesSearchAndReview() {
        val viewModel = KmdReaderViewModel(FakeWorkRepository())

        viewModel.onAction(KmdReaderAction.OpenSearch)
        assertEquals(true, viewModel.state.value.deskStack.isSearchOpen)

        viewModel.onAction(KmdReaderAction.OpenSettings)
        assertEquals(true, viewModel.state.value.deskStack.isSettingsOpen)
        assertEquals(false, viewModel.state.value.deskStack.isSearchOpen)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun progressChangedUpdatesShelfStateWithoutVmRebuild() = runTest {
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )

        // 初始书架/历史都空（MockWorkRepository 的作品不在 local_library 里）。
        advanceUntilIdle()
        assertTrue(viewModel.state.value.shelfState.shelf.isEmpty())
        assertTrue(viewModel.state.value.shelfState.history.isEmpty())

        // 打开一个 mock 作品并阅读 → 首次阅读建 entry（onShelf=false, lastReadAt=null）。
        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        // entry 已建但 lastReadAt 仍 null → 不在历史里。
        assertTrue(
            "entry exists but not yet in history (no lastReadAt)",
            viewModel.state.value.shelfState.history.none { it.workId == "glass-rail" }
        )

        // 发出 ProgressChanged → updateProgress 写 lastReadAt → refreshShelf 刷新 shelfState。
        clock = 1_000L
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.3f, timeMs = 720, durationMs = 2400))
        advanceUntilIdle()

        // 同会话内、不重建 VM：glass-rail 应出现在历史列表。
        val history = viewModel.state.value.shelfState.history
        val historyItem = history.firstOrNull { it.workId == "glass-rail" }
        assertNotNull("history must include glass-rail after progress persisted", historyItem)
        assertEquals(false, historyItem!!.onShelf)
        assertEquals(0.3f, historyItem.readingProgress, 0.001f)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun progressChangedUpdatesExistingHistoryProgressWithoutVmRebuild() = runTest {
        var clock = 0L
        val localLibrary = InMemoryLocalLibraryRepository()
        val runtimeBridge = ManualRuntimeBridge()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            runtimeBridge = runtimeBridge,
            nowMillis = { clock },
            localLibrary = localLibrary
        )

        viewModel.onAction(KmdReaderAction.OpenWork("glass-rail"))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = "glass-rail", durationMs = 2400))
        advanceUntilIdle()

        // 第一笔进度 → history 出现 glass-rail，进度 0.3。
        clock = 1_000L
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.3f, timeMs = 720, durationMs = 2400))
        advanceUntilIdle()
        assertEquals(0.3f, viewModel.state.value.shelfState.history.first { it.workId == "glass-rail" }.readingProgress, 0.001f)

        // 第二笔进度（≥5s 后）→ 进度更新到 0.5 → shelfState 同步刷新，不重建 VM。
        clock = 6_001L
        runtimeBridge.emit(progressEvent("glass-rail", progress = 0.5f, timeMs = 1200, durationMs = 2400))
        advanceUntilIdle()
        assertEquals(
            "shelfState must reflect updated progress without VM rebuild",
            0.5f,
            viewModel.state.value.shelfState.history.first { it.workId == "glass-rail" }.readingProgress,
            0.001f
        )
    }

    // ── R3-G：加入书架 / 移出书架 ──────────────────────────────────────

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleShelfAddsNeverReadWorkToShelf() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        // glass-rail 从未读过、未导入 → DB 里无 entry。
        assertNull(localLibrary.getEntry("glass-rail"))

        viewModel.onAction(KmdReaderAction.ToggleShelf("glass-rail"))
        advanceUntilIdle()

        val entry = localLibrary.getEntry("glass-rail")
        assertNotNull("toggle must create entry for never-read work", entry)
        assertEquals(true, entry!!.onShelf)
        // shelfState.shelf 应包含 glass-rail。
        assertTrue(viewModel.state.value.shelfState.shelf.any { it.workId == "glass-rail" })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleShelfPutsExistingEntryOnShelf() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // 已有 entry，onShelf=false（读过但未加入书架）。
        localLibrary.upsertEntry(
            shelfEntry("glass-rail", onShelf = false, importedAt = null, lastReadAt = 1000L)
        )
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        viewModel.onAction(KmdReaderAction.ToggleShelf("glass-rail"))
        advanceUntilIdle()

        assertEquals(true, localLibrary.getEntry("glass-rail")?.onShelf)
        // shelfState：shelf 包含，history 排除（onShelf=true 被 history filter 剔除）。
        assertTrue(viewModel.state.value.shelfState.shelf.any { it.workId == "glass-rail" })
        assertTrue(viewModel.state.value.shelfState.history.none { it.workId == "glass-rail" })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleShelfRemovesFromShelf() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // 已在书架。
        localLibrary.upsertEntry(
            shelfEntry("glass-rail", onShelf = true, importedAt = 2000L, lastReadAt = null)
        )
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        viewModel.onAction(KmdReaderAction.ToggleShelf("glass-rail"))
        advanceUntilIdle()

        assertEquals(false, localLibrary.getEntry("glass-rail")?.onShelf)
        // 移出后不在 shelf 里。lastReadAt=null → 也不在 history 里。
        assertTrue(viewModel.state.value.shelfState.shelf.none { it.workId == "glass-rail" })
        assertTrue(viewModel.state.value.shelfState.history.none { it.workId == "glass-rail" })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleShelfCreatesEntryWithDefaultFieldsWhenNoneExists() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        viewModel.onAction(KmdReaderAction.ToggleShelf("rain-city"))
        advanceUntilIdle()

        val entry = localLibrary.getEntry("rain-city")
        assertNotNull(entry)
        assertEquals(true, entry!!.onShelf)
        // 新建 entry 的进度/时间字段应为默认值，不应有残留。
        assertEquals(0f, entry.readingProgress, 0.001f)
        assertNull(entry.readingTimeMs)
        assertNull(entry.readingDurationMs)
        assertNull(entry.lastReadAt)
        // importedAt 也为 null（这是加入书架，不是导入）。
        assertNull(entry.importedAt)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rapidToggleShelfTogglesExactlyTwice() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // 预置 entry，onShelf=false。
        localLibrary.upsertEntry(
            shelfEntry("glass-rail", onShelf = false, importedAt = null, lastReadAt = null)
        )
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        // 连续两次 toggle：false → true → false。mutex 串行化后应翻转两次。
        viewModel.onAction(KmdReaderAction.ToggleShelf("glass-rail"))
        viewModel.onAction(KmdReaderAction.ToggleShelf("glass-rail"))
        advanceUntilIdle()

        // 两次翻转后应回到初始值 false。无 mutex 时并发竞态可能导致两次都读到 false
        // 并都写入 true，最终只翻转一次（停在 true）。
        assertEquals(
            "rapid double toggle must net to original value (mutex serializes)",
            false,
            localLibrary.getEntry("glass-rail")?.onShelf
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleShelfFromBrowseDeskActionWorks() = runTest {
        // BrowseDesk dispatches ToggleShelf(workId) with the specific work's id,
        // 不依赖 deskStack.currentWorkId。验证此路径正确执行。
        val localLibrary = InMemoryLocalLibraryRepository()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        // 模拟 BrowseDesk 的 onToggleShelf("star-manual") dispatch。
        viewModel.onAction(KmdReaderAction.ToggleShelf("star-manual"))
        advanceUntilIdle()

        assertEquals(true, localLibrary.getEntry("star-manual")?.onShelf)
        assertTrue(viewModel.state.value.shelfState.shelf.any { it.workId == "star-manual" })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleShelfNoOpWhenWorkNotInCatalog() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        val viewModel = KmdReaderViewModel(
            repository = FakeWorkRepository(),
            localLibrary = localLibrary
        )
        advanceUntilIdle()

        // workId 不在 works 列表且无 entry → toggle 不创建空 entry。
        viewModel.onAction(KmdReaderAction.ToggleShelf("nonexistent-work"))
        advanceUntilIdle()

        assertNull(localLibrary.getEntry("nonexistent-work"))
    }

    /** R3-F 测试 helper：构建可定制的 LocalLibraryEntry。 */
    private fun shelfEntry(
        workId: String,
        onShelf: Boolean,
        importedAt: Long?,
        lastReadAt: Long?,
        kmdSource: String? = null,
        bundleId: String? = null
    ): LocalLibraryEntry {
        val work = MockWorks.works.firstOrNull { it.id == workId }
        return LocalLibraryEntry(
            workId = workId,
            source = work?.sourceType ?: WorkSourceType.Local,
            onShelf = onShelf,
            title = work?.title ?: workId,
            authorName = work?.authorName ?: "未知",
            presentationMode = work?.presentation?.mode ?: PresentationMode.Scroll,
            aspectRatio = work?.presentation?.aspectRatio ?: "16:9",
            kmdSource = kmdSource,
            contentUri = work?.contentUri ?: "",
            readingProgress = if (lastReadAt != null) 0.5f else 0f,
            readingTimeMs = null,
            readingDurationMs = null,
            lastReadAt = lastReadAt,
            importedAt = importedAt,
            cachedAt = null,
            bundleId = bundleId
        )
    }

    /** 把 reader 推进到 Ready 态的共用设置（StartIssueDraft 需要 Ready session 采集锚点）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.bringReaderToReady(
        viewModel: KmdReaderViewModel,
        runtimeBridge: ManualRuntimeBridge,
        workId: String
    ) {
        viewModel.onAction(KmdReaderAction.OpenWork(workId))
        viewModel.onAction(KmdReaderAction.OpenReader)
        advanceUntilIdle()
        runtimeBridge.emit(ReaderRuntimeEvent.Ready(workId = workId, durationMs = 2400))
        advanceUntilIdle()
    }

    private fun progressEntry(
        workId: String,
        progress: Float,
        durationMs: Long,
        activeRevisionId: String? = null
    ): LocalLibraryEntry {
        val work = MockWorks.works.first { it.id == workId }
        return LocalLibraryEntry(
            workId = workId,
            source = work.sourceType,
            onShelf = false,
            title = work.title,
            authorName = work.authorName,
            presentationMode = work.presentation.mode,
            aspectRatio = work.presentation.aspectRatio,
            kmdSource = null,
            contentUri = work.contentUri,
            readingProgress = progress,
            readingTimeMs = null,
            readingDurationMs = durationMs,
            lastReadAt = null,
            importedAt = null,
            cachedAt = null,
            activeRevisionId = activeRevisionId
        )
    }

    private fun progressEvent(
        workId: String,
        progress: Float,
        timeMs: Long,
        durationMs: Long
    ): ReaderRuntimeEvent.ProgressChanged = ReaderRuntimeEvent.ProgressChanged(
        workId = workId,
        progress = progress,
        positionPayload = "line:0",
        timeMs = timeMs,
        durationMs = durationMs
    )
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

/**
 * [LocalLibraryRepository] 的可挂起替身：第一次 [getDraftsByType] 调用挂起在
 * [firstGetDraftsByTypeGate] 上，由测试控制何时（及返回什么）。
 *
 * 用于精确制造“迟到恢复”竞态窗口：A 的草稿恢复查询被卡住，等 B 起草完成后再释放，
 * 以验证 [KmdReaderViewModel.startIssueDraftWithPersistence] 里的 requestedWorkId guard。
 * 其余方法委派给内存实现，保持其它行为不变。
 *
 * 注意：不能用 [Mutex]/synchronized 包裹 gate.await()——挂起时仍持锁会让后续调用
 * （B 的查询）一并阻塞，把竞态窗口塌缩掉。UnconfinedTestDispatcher 单线程且无抢占，
 * 这里用普通 Boolean 标志位即可安全区分“第一次调用”。
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class ControllableLocalLibraryRepository(
    private val firstGetDraftsByTypeGate: CompletableDeferred<List<LocalDraft>>
) : LocalLibraryRepository {
    private val delegate = InMemoryLocalLibraryRepository()
    private var firstQuerySeen = false

    override suspend fun getDraftsByType(workId: String, type: String): List<LocalDraft> {
        if (!firstQuerySeen) {
            firstQuerySeen = true
            // 第一次查询挂起到 gate 完成——返回值由测试注入。
            // 不在持锁状态下 await，否则后续 getDraftsByType 会被一起卡死。
            return firstGetDraftsByTypeGate.await()
        }
        return delegate.getDraftsByType(workId, type)
    }

    override suspend fun getEntry(workId: String): LocalLibraryEntry? = delegate.getEntry(workId)
    override suspend fun getShelf(): List<LocalLibraryEntry> = delegate.getShelf()
    override suspend fun getHistory(): List<LocalLibraryEntry> = delegate.getHistory()
    override suspend fun upsertEntry(entry: LocalLibraryEntry) = delegate.upsertEntry(entry)
    override suspend fun updateProgress(
        workId: String, progress: Float, timeMs: Long?, durationMs: Long?, now: Long, revisionId: String?
    ) = delegate.updateProgress(workId, progress, timeMs, durationMs, now, revisionId)
    override suspend fun setOnShelf(workId: String, onShelf: Boolean) =
        delegate.setOnShelf(workId, onShelf)
    override suspend fun removeEntry(workId: String) = delegate.removeEntry(workId)
    override suspend fun getLatestRevision(workId: String): LocalRevision? =
        delegate.getLatestRevision(workId)
    override suspend fun findRevisionByContentHash(workId: String, contentHash: String): LocalRevision? =
        delegate.findRevisionByContentHash(workId, contentHash)
    override suspend fun getRevisionsForWork(workId: String): List<LocalRevision> =
        delegate.getRevisionsForWork(workId)
    override suspend fun saveRevision(revision: LocalRevision) = delegate.saveRevision(revision)
    override suspend fun clearRevisionsForWork(workId: String) = delegate.clearRevisionsForWork(workId)
    override suspend fun getDrafts(workId: String): List<LocalDraft> = delegate.getDrafts(workId)
    override suspend fun saveDraft(draft: LocalDraft) = delegate.saveDraft(draft)
    override suspend fun deleteDraft(id: String) = delegate.deleteDraft(id)
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
