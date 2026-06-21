package com.example.kmd_reader.presentation

import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.mock.MockKmdSources
import com.example.kmd_reader.data.repository.InMemoryLocalLibraryRepository
import com.example.kmd_reader.data.repository.LocalLibraryEntry
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
    fun readyDoesNotRestoreSeekWhenDurationMismatched() = runTest {
        val localLibrary = InMemoryLocalLibraryRepository()
        // 持久化 duration=2400，但本次 Ready 上报 4800（换源/修订）→ 不恢复
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

        assertTrue("duration mismatch must skip restore seek", runtimeBridge.seekCalls.isEmpty())
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
    fun readyDoesNotRestoreSeekWhenSavedDurationIsNull() = runTest {
        // OQ（严格语义）：entry.readingDurationMs=null（旧 entry 无 duration 记录），
        // 即便 event 上报了 duration，也不恢复 seek（无可靠基准）。
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

        assertTrue(
            "must not restore seek when saved duration is null (no reliable baseline)",
            runtimeBridge.seekCalls.isEmpty()
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun readyDoesNotRestoreSeekWhenEventDurationIsNull() = runTest {
        // OQ（严格语义）：entry 有 duration 但 event.durationMs=null，也不恢复。
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

        assertTrue(
            "must not restore seek when event duration is null (no reliable baseline)",
            runtimeBridge.seekCalls.isEmpty()
        )
    }

    private fun progressEntry(
        workId: String,
        progress: Float,
        durationMs: Long
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
            cachedAt = null
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
