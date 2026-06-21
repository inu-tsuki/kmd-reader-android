package com.example.kmd_reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kmd_reader.data.MockWorkRepository
import com.example.kmd_reader.data.WorkRepository
import com.example.kmd_reader.data.repository.InMemoryLocalLibraryRepository
import com.example.kmd_reader.data.repository.LocalDraft
import com.example.kmd_reader.data.repository.LocalDraftTypes
import com.example.kmd_reader.data.repository.LocalLibraryEntry
import com.example.kmd_reader.data.repository.LocalLibraryRepository
import com.example.kmd_reader.domain.kmd.KmdSourceMetadataParser
import com.example.kmd_reader.domain.model.IssueSource
import com.example.kmd_reader.domain.model.KmdSourceSnapshot
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.policy.DeskStackPolicy
import com.example.kmd_reader.domain.policy.ReaderViewportPolicy
import com.example.kmd_reader.runtime.FakeReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import com.example.kmd_reader.runtime.ReaderRuntimeTimelineMarker
import com.example.kmd_reader.runtime.toReaderRuntimeAssetManifest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

class KmdReaderViewModel(
    private val repository: WorkRepository = MockWorkRepository(),
    private val runtimeBridge: ReaderRuntimeBridge = FakeReaderRuntimeBridge(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val localLibrary: LocalLibraryRepository = InMemoryLocalLibraryRepository()
) : ViewModel() {
    val runtimeBridgeForHost: ReaderRuntimeBridge
        get() = runtimeBridge

    private val _state = MutableStateFlow(KmdReaderState())
    val state: StateFlow<KmdReaderState> = _state.asStateFlow()

    private val effects = Channel<KmdReaderEffect>(capacity = Channel.BUFFERED)
    val effectFlow = effects.receiveAsFlow()

    // 进度持久化：按 workId 记录上次落盘时间戳（与 nowMillis 同一时钟）。播放中按
    // PROGRESS_SAVE_INTERVAL_MS 节流写库，onCleared 再兜底落一次。
    // F3 修复（Medium）：per-work 追踪。原为 ViewModel 全局单值，切 work 时新 work 的首个
    // 进度事件会误中旧 work 的节流窗口被丢弃。改为 map by workId，map 不含该 key 即首次落盘。
    // （这也替代了原先的 hasSavedProgress 标志——不存在 key 即首次，无需独立标志。）
    private val lastProgressSavedAt = mutableMapOf<String, Long>()

    // R3-C：issue 草稿 debounce 自动保存。按 draftId 记录上次落盘时间戳，与进度节流同构。
    private val lastDraftSavedAt = mutableMapOf<String, Long>()

    companion object {
        // 播放期间进度落盘节流间隔。单本 20min 作品写 ~240 次，断电最多丢 5s 进度。
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        // 草稿落盘节流间隔。草稿比进度更敏感（用户逐字编辑），1s 窗口 + onCleared 兜底。
        private const val DRAFT_SAVE_INTERVAL_MS = 1_000L
    }

    init {
        observeRuntimeEvents()
        refreshWorks()
    }

    fun onAction(action: KmdReaderAction) {
        when (action) {
            KmdReaderAction.RefreshWorks -> refreshWorks()
            is KmdReaderAction.OpenWork -> {
                // F2（续）：OpenWork 会重建 IssueFocusState 丢弃当前 draft，需先 trailing flush。
                flushDraftSynchronously()
                reduce(action)
                loadIssues(action.workId)
            }
            KmdReaderAction.OpenReview -> {
                reduce(action)
                _state.value.deskStack.currentWorkId?.let { workId ->
                    loadIssues(workId)
                    loadSourceSnapshot(workId)
                    requestRuntimeInspection(workId)
                }
            }
            is KmdReaderAction.OpenReaderCompanion -> openReaderCompanion(action)
            KmdReaderAction.CloseReview -> closeReview()
            KmdReaderAction.CloseReaderCompanion -> closeReaderCompanion()
            KmdReaderAction.OpenReader -> openReader()
            KmdReaderAction.PlayReader -> playReader()
            KmdReaderAction.PauseReader -> pauseReader()
            is KmdReaderAction.SeekReader -> seekReader(action.progress)
            KmdReaderAction.SubmitIssueDraft -> submitIssueDraft()
            KmdReaderAction.StartIssueDraftFromPlayback -> startIssueDraftWithPersistence()
            is KmdReaderAction.UpdateIssueDraftMessage,
            is KmdReaderAction.UpdateIssueDraftSuggestion -> {
                reduce(action)
                scheduleDraftSave()
            }
            KmdReaderAction.CancelIssueDraft -> {
                deleteCurrentDraftIfAny()
                reduce(action)
            }
            // F2 修复：这些 action 会清空/替换 issueDraft（reducer 里 issueDraft = null）。
            // leading-edge throttle 不会安排 trailing save，故在此同步 flush 最后一笔，
            // 防用户在节流窗口内打完最后一字后切走导致丢失。
            is KmdReaderAction.SelectSourceLine,
            KmdReaderAction.ClearIssueFocus,
            is KmdReaderAction.SelectIssue -> {
                flushDraftSynchronously()
                reduce(action)
            }
            is KmdReaderAction.JumpIssueToPlayback -> jumpIssueToPlayback(action.issueId)
            KmdReaderAction.JumpSelectedSourceLineToPlayback -> jumpSelectedSourceLineToPlayback()
            KmdReaderAction.RetryReaderRuntime -> retryReaderRuntime()
            is KmdReaderAction.UpdateReaderHostSize -> updateReaderHostSize(action)
            KmdReaderAction.OpenImport -> {
                reduce(action)
                sendEffect(KmdReaderEffect.OpenImportPicker)
            }
            else -> reduce(action)
        }
    }

    private fun openReader() {
        reduce(KmdReaderAction.OpenReader)
        loadCurrentReaderWork()
    }

    private fun retryReaderRuntime() {
        reduce(KmdReaderAction.RetryReaderRuntime)
        loadCurrentReaderWork()
    }

    private fun openReaderCompanion(action: KmdReaderAction.OpenReaderCompanion) {
        val shouldDisableInspection =
            _state.value.readerCompanion.active == ReaderCompanionType.Review &&
                action.type != ReaderCompanionType.Review
        reduce(action)
        if (shouldDisableInspection) {
            disableRuntimeInspection()
        }
        if (action.type == ReaderCompanionType.Review || action.type == ReaderCompanionType.Issues) {
            _state.value.deskStack.currentWorkId?.let { workId ->
                loadIssues(workId)
                loadSourceSnapshot(workId)
                if (action.type == ReaderCompanionType.Review) {
                    requestRuntimeInspection(workId)
                }
            }
        }
    }

    private fun closeReview() {
        val shouldDisableInspection = _state.value.readerCompanion.active == ReaderCompanionType.Review
        reduce(KmdReaderAction.CloseReview)
        if (shouldDisableInspection) {
            disableRuntimeInspection()
        }
    }

    private fun closeReaderCompanion() {
        val shouldDisableInspection = _state.value.readerCompanion.active == ReaderCompanionType.Review
        reduce(KmdReaderAction.CloseReaderCompanion)
        if (shouldDisableInspection) {
            disableRuntimeInspection()
        }
    }

    private fun loadCurrentReaderWork() {
        val workId = _state.value.deskStack.currentWorkId
        val work = _state.value.selectedWork

        if (workId == null || work == null) {
            sendEffect(KmdReaderEffect.ShowMessage("请先选择一个 KMD 作品。"))
            return
        }

        runtimeBridge.prepareLoad(workId)
        _state.update {
            it.copy(readerSession = ReaderSessionState.Loading(workId = workId))
        }
        sendEffect(KmdReaderEffect.LoadRuntime(workId))

        viewModelScope.launch {
            runCatching {
                val source = repository.getWorkSource(workId = work.id)
                    ?: error("当前作品还没有可播放的 KMD 源文本。")
                // R3-B 步骤2：首次阅读自动建 entry（onShelf=false）。
                // updateProgress 在 entry 不存在时是空操作，所以必须先于任何进度写入建 entry。
                // 走 get-then-insert，不盲目 upsert，避免覆盖已有进度/时间字段。
                if (localLibrary.getEntry(work.id) == null) {
                    localLibrary.upsertEntry(work.toLocalLibraryEntry())
                }
                val sourceSnapshot = KmdSourceSnapshot.fromSource(
                    workId = work.id,
                    revisionId = work.script.activeRevisionId,
                    content = source,
                    fetchedAtMillis = nowMillis()
                )
                val sourceHints = KmdSourceMetadataParser.parsePresentationHints(source)
                val readerViewport = _state.value.readerViewport
                val resolvedViewport = ReaderViewportPolicy.resolve(
                    work = work,
                    hostWidthPx = readerViewport.hostWidthPx,
                    hostHeightPx = readerViewport.hostHeightPx,
                    sourceHints = sourceHints
                )
                _state.update {
                    if (it.deskStack.currentWorkId == workId) {
                        it.copy(
                            readerViewport = resolvedViewport,
                            sourceSnapshotsByWorkId = it.sourceSnapshotsByWorkId + (
                                work.id to sourceSnapshot
                                )
                        )
                    } else {
                        it
                    }
                }
                runtimeBridge.load(
                    ReaderLoadRequest(
                        work = work,
                        source = source,
                        assetManifest = work.assetManifest?.toReaderRuntimeAssetManifest(),
                        settings = ReaderViewportPolicy.settingsFor(resolvedViewport)
                    )
                )
            }.onFailure { error ->
                _state.update {
                    val currentSession = it.readerSession
                    it.copy(
                        readerSession = ReaderSessionState.Failed(
                            workId = workId,
                            message = error.message ?: "Reader Runtime 加载失败",
                            phase = currentSession.phaseOrNull()
                                ?: ReaderSessionPhase.WorkLoading,
                            recoverable = true,
                            sessionId = currentSession.sessionIdOrNull(),
                            capabilities = currentSession.capabilitiesOrNull(),
                            diagnostics = runtimeBridge.debugSnapshot()
                        ),
                        readerChrome = it.readerChrome.show(mode = ReaderChromeMode.Error)
                    )
                }
            }
        }
    }

    private fun observeRuntimeEvents() {
        viewModelScope.launch {
            runtimeBridge.events.collect(::handleRuntimeEvent)
        }
        viewModelScope.launch {
            runtimeBridge.attach()
        }
    }

    private fun handleRuntimeEvent(event: ReaderRuntimeEvent) {
        when (event) {
            is ReaderRuntimeEvent.TransportReady -> {
                _state.update { state ->
                    if (state.readerSession is ReaderSessionState.Failed) {
                        return@update state
                    }
                    val workId = state.readerSession.workIdOrNull()
                        ?: state.deskStack.currentWorkId
                        ?: return@update state
                    state.copy(
                        readerSession = ReaderSessionState.Loading(
                            workId = workId,
                            phase = ReaderSessionPhase.TransportReady,
                            runtimeName = event.runtime,
                            capabilities = event.capabilities,
                            sessionId = event.sessionId
                        )
                    )
                }
            }
            is ReaderRuntimeEvent.Ready -> {
                // F1 修复（High）：workId 门控。WebView 单例复用，切 work 后旧 work 的迟到
                // Ready 仍能到达。以 deskStack.currentWorkId 为基准（OpenWork 立即更新它，
                // 比 readerSession 更早反映切换，覆盖过渡窗口）。迟到的 A.Ready 在 deskStack
                // 已是 B 时被拦截——session 不被改回 A，也不触发 restoreSeek。
                var applied = false
                _state.update {
                    if (it.readerSession.isFailedFor(event.workId)) {
                        return@update it
                    }
                    if (event.workId != it.deskStack.currentWorkId) {
                        return@update it
                    }
                    applied = true
                    val current = it.readerSession
                    it.copy(
                        readerSession = ReaderSessionState.Ready(
                            workId = event.workId,
                            progress = 0f,
                            isPlaying = false,
                            playbackState = "ready",
                            durationMs = event.durationMs,
                            timelineMarkers = event.timelineMarkers,
                            capabilities = current.capabilitiesOrNull(),
                            sessionId = event.sessionId ?: current.sessionIdOrNull()
                        ),
                        readerChrome = it.readerChrome.show(mode = ReaderChromeMode.Reading)
                    )
                }
                // 仅在 update 真正应用了 Ready（即事件属于当前 work）时才恢复 seek。
                // 迟到事件 applied=false，直接跳过 restoreSeekOnReady。
                if (applied) {
                    // R3-B 步骤3：Ready 后按持久化进度恢复 seek。
                    // 此时 session 已是 Ready，满足 seek 时序。直接调 bridge.seek 而非 onAction，
                    // 避免重复 reduce。失败静默（不影响播放）。
                    restoreSeekOnReady(event.workId, event.durationMs)
                }
            }
            is ReaderRuntimeEvent.ProgressChanged -> {
                val currentSession = _state.value.readerSession
                if (currentSession.isFailedFor(event.workId)) {
                    return
                }
                // F1 修复（High）：workId 门控。迟到 A.ProgressChanged 在 deskStack 已是 B 时
                // 整体丢弃——既不改 session，也不写 Room（否则会写脏 B 的进度）。
                if (event.workId != _state.value.deskStack.currentWorkId) {
                    return
                }
                val readySession = (currentSession as? ReaderSessionState.Ready)
                    ?.takeIf { it.workId == event.workId }
                val currentMarker = resolveCurrentMarker(event, readySession)
                val currentLine = event.line
                    ?: currentMarker?.line
                    ?: event.positionPayload.lineNumberHint()
                    ?: readySession?.currentLine
                _state.update {
                    it.copy(
                        readerSession = ReaderSessionState.Ready(
                            workId = event.workId,
                            progress = event.progress,
                            isPlaying = readySession?.isPlaying ?: false,
                            playbackState = readySession?.playbackState,
                            timeMs = event.timeMs ?: readySession?.timeMs,
                            durationMs = event.durationMs ?: readySession?.durationMs,
                            currentLine = currentLine,
                            currentMarkerId = event.markerId
                                ?: currentMarker?.id
                                ?: readySession?.currentMarkerId,
                            timelineMarkers = readySession?.timelineMarkers.orEmpty(),
                            capabilities = currentSession.capabilitiesOrNull(),
                            sessionId = event.sessionId ?: currentSession.sessionIdOrNull()
                        )
                    )
                }
                // R3-B 步骤4：进度节流落盘。
                persistProgressIfNeeded(
                    workId = event.workId,
                    progress = event.progress,
                    timeMs = event.timeMs ?: readySession?.timeMs,
                    durationMs = event.durationMs ?: readySession?.durationMs
                )
            }
            is ReaderRuntimeEvent.PlaybackStateChanged -> {
                val currentSession = _state.value.readerSession
                if (currentSession.isFailedFor(event.workId)) {
                    return
                }
                // F1（续）：PlaybackStateChanged 同样需 deskStack 门控。迟到的 A 事件在
                // 切到 B 后会把 session 改回 A 且 progress 退成 0f（readySession.takeIf 失败
                // → null → 0f）；随后 onCleared flush 会用 0f 覆盖 A 的真实进度。
                if (event.workId != _state.value.deskStack.currentWorkId) {
                    return
                }
                if (event.state == "loading") {
                    _state.update {
                        it.copy(
                            readerSession = ReaderSessionState.Loading(
                                workId = event.workId,
                                phase = ReaderSessionPhase.WorkLoading,
                                capabilities = currentSession.capabilitiesOrNull(),
                                sessionId = event.sessionId ?: currentSession.sessionIdOrNull()
                            ),
                            readerChrome = it.readerChrome.show(mode = ReaderChromeMode.Reading)
                        )
                    }
                } else {
                    val readySession = (currentSession as? ReaderSessionState.Ready)
                        ?.takeIf { it.workId == event.workId }
                    _state.update {
                        it.copy(
                            readerSession = ReaderSessionState.Ready(
                                workId = event.workId,
                                progress = readySession?.progress ?: 0f,
                                isPlaying = event.isPlaying,
                                playbackState = event.state,
                                timeMs = readySession?.timeMs,
                                durationMs = readySession?.durationMs,
                                currentLine = readySession?.currentLine,
                                currentMarkerId = readySession?.currentMarkerId,
                                timelineMarkers = readySession?.timelineMarkers.orEmpty(),
                                capabilities = currentSession.capabilitiesOrNull(),
                                sessionId = event.sessionId ?: currentSession.sessionIdOrNull()
                            ),
                            readerChrome = if (event.isPlaying) {
                                it.readerChrome
                            } else {
                                it.readerChrome.show(mode = ReaderChromeMode.Reading)
                            }
                        )
                    }
                }
            }
            is ReaderRuntimeEvent.InspectionReported -> {
                _state.update {
                    it.copy(
                        issuesByWorkId = it.issuesByWorkId + (
                            event.workId to mergeIssues(
                                current = it.issuesByWorkId[event.workId].orEmpty(),
                                incoming = event.issues
                            )
                            )
                    )
                }
            }
            is ReaderRuntimeEvent.Failed -> {
                // F1（续）：Failed 门控。迟到的 A 失败事件会把当前 B 的 session 切成
                // Failed(A)，导致 B 的最后一笔进度无法经 onCleared 兜底落盘。
                // event.workId 非空且不等于 deskStack 当前 → 明确属于旧 work，丢弃。
                // event.workId 为 null 时无法判定归属：保留原 fallback（归咎当前 session），
                // 保证无归属信息的失败仍对用户可见，不因防迟到而吞掉错误。
                val resolvedWorkId = event.workId
                if (resolvedWorkId != null && resolvedWorkId != _state.value.deskStack.currentWorkId) {
                    return
                }
                _state.update {
                    val currentSession = it.readerSession
                    it.copy(
                        readerSession = ReaderSessionState.Failed(
                            workId = resolvedWorkId ?: it.deskStack.currentWorkId.orEmpty(),
                            message = event.message,
                            phase = currentSession.phaseOrNull()
                                ?: ReaderSessionPhase.WorkLoading,
                            code = event.code,
                            commandId = event.commandId,
                            recoverable = event.recoverable ?: true,
                            sessionId = event.sessionId ?: currentSession.sessionIdOrNull(),
                            capabilities = currentSession.capabilitiesOrNull(),
                            diagnostics = runtimeBridge.debugSnapshot()
                        ),
                        readerChrome = it.readerChrome.show(mode = ReaderChromeMode.Error)
                    )
                }
            }
        }
    }

    private fun playReader() {
        if (_state.value.readerSession !is ReaderSessionState.Ready) {
            sendEffect(KmdReaderEffect.ShowMessage("作品还没有加载完成。"))
            return
        }
        reduce(KmdReaderAction.ShowReaderChrome())
        viewModelScope.launch {
            runCatching {
                runtimeBridge.play()
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("播放命令发送失败"))
            }
        }
    }

    private fun pauseReader() {
        if (_state.value.readerSession !is ReaderSessionState.Ready) {
            return
        }
        reduce(KmdReaderAction.PauseReader)
        viewModelScope.launch {
            runCatching {
                runtimeBridge.pause()
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("暂停命令发送失败"))
            }
        }
    }

    private fun seekReader(progress: Float) {
        if (_state.value.readerSession !is ReaderSessionState.Ready) {
            return
        }
        reduce(KmdReaderAction.SeekReader(progress))
        viewModelScope.launch {
            runCatching {
                runtimeBridge.seek(progress)
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("进度跳转失败"))
            }
        }
    }

    // R3-B 步骤3：Ready 后从本地库读进度并 seek。
    // 守卫：progress ∈ (0,1) 且 readingDurationMs 与 event.durationMs 双方都非 null 且相等。
    //   - 任一方为 null（无可靠基准）则不恢复（OQ 审阅：严格语义）。
    //   - duration 不匹配（换源/修订）则不恢复，防"旧进度 + 新 duration = 跳到错位置"。
    // F2 修复：seek 成功后回写 state.progress。否则 Ready 设的 progress=0f 会留存到
    //   runtime echo ProgressChanged 之前；若此窗口内 onCleared，flush 会写 0f 覆盖恢复点。
    private fun restoreSeekOnReady(workId: String, durationMs: Long?) {
        viewModelScope.launch {
            runCatching {
                val entry = localLibrary.getEntry(workId) ?: return@launch
                val progress = entry.readingProgress
                if (progress <= 0f || progress >= 1f) return@launch
                val savedDuration = entry.readingDurationMs
                if (savedDuration == null || durationMs == null || savedDuration != durationMs) {
                    return@launch
                }
                runtimeBridge.seek(progress)
                // 回写恢复后的进度，使 onCleared flush 拿到恢复点而非 0f。
                _state.update {
                    val session = it.readerSession as? ReaderSessionState.Ready
                    if (session != null && session.workId == workId) {
                        it.copy(readerSession = session.copy(progress = progress))
                    } else {
                        it
                    }
                }
            }
        }
    }

    // R3-B 步骤4：ProgressChanged 节流写库。距上次落盘不足 PROGRESS_SAVE_INTERVAL_MS 则跳过。
    // 失败静默（不阻断播放、不弹消息）。
    // F3 修复：节流计数器按 workId 追踪，避免切 work 时新 work 首事件被旧 work 窗口误杀。
    private fun persistProgressIfNeeded(
        workId: String,
        progress: Float,
        timeMs: Long?,
        durationMs: Long?
    ) {
        val now = nowMillis()
        // map 不含该 workId 即首次落盘，无脑写一笔；之后按 PROGRESS_SAVE_INTERVAL_MS 节流。
        val last = lastProgressSavedAt[workId]
        if (last != null && now - last < PROGRESS_SAVE_INTERVAL_MS) {
            return
        }
        lastProgressSavedAt[workId] = now
        viewModelScope.launch {
            runCatching {
                localLibrary.updateProgress(workId, progress, timeMs, durationMs, now)
            }
        }
    }

    // ===== R3-C：issue 草稿本地缓冲 =====

    /**
     * R3-C 步骤3：起草时先建 draft 骨架（reducer 采集锚点），再生成持久化 id，
     * 最后查库恢复未提交草稿的 message/suggestion/severity。
     * reducer 保持纯函数——UUID 生成和 Room IO 都在 VM 侧。
     */
    private fun startIssueDraftWithPersistence() {
        reduce(KmdReaderAction.StartIssueDraftFromPlayback)
        val draft = _state.value.issueFocus.issueDraft ?: return
        // 捕获发起时的 workId 作为 request token。异步恢复返回时校验当前 issueDraft 仍是
        // 这个 work（用户可能已切到别的 work 起草），是则丢弃迟到结果，防覆盖 B 的草稿。
        val requestedWorkId = draft.workId
        viewModelScope.launch {
            runCatching {
                val persisted = localLibrary.getDraftsByType(requestedWorkId, LocalDraftTypes.ISSUE)
                // F1 修复：Room 查询返回时校验当前 draft 仍属于发起时的 work。
                val current = _state.value.issueFocus.issueDraft
                if (current == null || current.workId != requestedWorkId) {
                    return@runCatching
                }
                if (persisted.isEmpty()) {
                    // 无持久化草稿——生成新 id。
                    reduce(KmdReaderAction.AssignIssueDraftId("draft-${UUID.randomUUID()}"))
                    return@runCatching
                }
                val restored = persisted.first()
                // 复用持久化草稿的 id，使后续 deleteCurrentDraftIfAny 能删对行。
                reduce(KmdReaderAction.AssignIssueDraftId(restored.id))
                val restoredDraft = runCatching { issueDraftFromJson(restored.payload) }.getOrNull()
                if (restoredDraft != null) {
                    // 恢复文本字段 + severity，不恢复锚点（每次重新采集更安全）。
                    reduce(
                        KmdReaderAction.UpdateIssueDraftFromPersisted(
                            message = restoredDraft.message,
                            suggestion = restoredDraft.suggestion,
                            severity = restoredDraft.severity
                        )
                    )
                }
            }
        }
    }

    /**
     * R3-C 步骤2：debounce 自动保存。镜像进度节流（时钟比较，不用 delay/Job）。
     * 窗口内的 keystroke 跳过，窗口边界处的下次 keystroke 写入；onCleared 兜底最后一笔。
     */
    private fun scheduleDraftSave() {
        val draft = _state.value.issueFocus.issueDraft ?: return
        if (draft.id.isBlank()) return
        val now = nowMillis()
        val last = lastDraftSavedAt[draft.id]
        if (last != null && now - last < DRAFT_SAVE_INTERVAL_MS) return
        lastDraftSavedAt[draft.id] = now
        viewModelScope.launch {
            runCatching {
                localLibrary.saveDraft(
                    LocalDraft(
                        id = draft.id,
                        workId = draft.workId,
                        type = LocalDraftTypes.ISSUE,
                        payload = draft.toJson(),
                        updatedAt = now
                    )
                )
            }
        }
    }

    /**
     * R3-C 步骤4：提交/取消时删除草稿（已转为 issue 或用户放弃）。
     * viewModelScope.launch 异步删——onCleared 路径用 [flushDraftOnCleared] 同步删。
     */
    private fun deleteCurrentDraftIfAny() {
        val draft = _state.value.issueFocus.issueDraft ?: return
        if (draft.id.isBlank()) return
        lastDraftSavedAt.remove(draft.id)
        viewModelScope.launch {
            runCatching { localLibrary.deleteDraft(draft.id) }
        }
    }

    /**
     * R3-C 步骤2 兜底：onCleared 时同步存一次当前草稿（防"打完最后一字不按键就退出"丢失）。
     * 与 [flushProgressOnCleared] 同理，viewModelScope 已取消，用 runBlocking。
     */
    internal fun flushDraftOnCleared() {
        val draft = _state.value.issueFocus.issueDraft ?: return
        if (draft.id.isBlank()) return
        runCatching {
            runBlocking {
                localLibrary.saveDraft(
                    LocalDraft(
                        id = draft.id,
                        workId = draft.workId,
                        type = LocalDraftTypes.ISSUE,
                        payload = draft.toJson(),
                        updatedAt = nowMillis()
                    )
                )
            }
        }
    }

    /**
     * F2 修复：导航型 action（SelectSourceLine/ClearIssueFocus/SelectIssue）清空/替换
     * issueDraft 前的 trailing flush。忽略节流窗口立即存——这些 action 是草稿结束的信号。
     * viewModelScope 仍活着，用 launch 异步存（与 scheduleDraftSave 同）。
     */
    private fun flushDraftSynchronously() {
        val draft = _state.value.issueFocus.issueDraft ?: return
        if (draft.id.isBlank()) return
        val now = nowMillis()
        lastDraftSavedAt[draft.id] = now
        viewModelScope.launch {
            runCatching {
                localLibrary.saveDraft(
                    LocalDraft(
                        id = draft.id,
                        workId = draft.workId,
                        type = LocalDraftTypes.ISSUE,
                        payload = draft.toJson(),
                        updatedAt = now
                    )
                )
            }
        }
    }

    private fun submitIssueDraft() {
        val state = _state.value
        val draft = state.issueFocus.issueDraft ?: return
        // R3-C 步骤4：提交后删除草稿（已转为 issue，不再需要缓冲）。
        deleteCurrentDraftIfAny()
        val message = draft.message.trim().ifBlank {
            draft.sourceRange?.let { "播放到 ${it.lineLabel} 时需要确认表现。" }
                ?: "阅读播放中发现一个待确认问题。"
        }
        val suggestion = draft.suggestion.trim().ifBlank {
            "请在审阅讨论中补充期望表现、复现步骤或修改建议。"
        }
        val issue = ScriptIssue(
            id = "local-${nowMillis()}",
            workId = draft.workId,
            severity = draft.severity,
            source = IssueSource.Runtime,
            location = draft.sourceRange?.lineLabel
                ?: draft.playbackAnchor?.line?.let { "line $it" }
                ?: "reader-runtime",
            message = message,
            suggestion = suggestion
        )

        _state.update {
            val anchors = draft.playbackAnchor?.let { anchor ->
                it.issueFocus.playbackAnchorsByIssueId + (issue.id to anchor)
            } ?: it.issueFocus.playbackAnchorsByIssueId
            it.copy(
                issuesByWorkId = it.issuesByWorkId + (
                    draft.workId to (it.issuesByWorkId[draft.workId].orEmpty() + issue)
                    ),
                issueFocus = it.issueFocus.copy(
                    selectedIssueId = issue.id,
                    focusedSourceRange = draft.sourceRange,
                    focusedPlaybackAnchor = draft.playbackAnchor,
                    issueDraft = null,
                    playbackAnchorsByIssueId = anchors
                ),
                deskStack = DeskStackPolicy.setReviewMessage(
                    it.deskStack,
                    "已创建本地问题：${issue.location}"
                )
            )
        }
    }

    private fun jumpIssueToPlayback(issueId: String) {
        reduce(KmdReaderAction.JumpIssueToPlayback(issueId))
        val state = _state.value
        val readySession = state.readerSession as? ReaderSessionState.Ready
        if (readySession == null || readySession.workId != state.deskStack.currentWorkId) {
            sendEffect(KmdReaderEffect.ShowMessage("作品还没有加载完成，暂时不能跳转播放位置。"))
            return
        }

        val issue = state.issuesByWorkId[readySession.workId]
            .orEmpty()
            .firstOrNull { it.id == issueId }
        val snapshot = state.sourceSnapshotsByWorkId[readySession.workId]
        val issueLine = issue
            ?.let { snapshot?.lineNumberForIssue(it) }
            ?: state.issueFocus.focusedSourceRange?.startLine
        val anchor = state.issueFocus.playbackAnchorsByIssueId[issueId]
            ?: state.issueFocus.focusedPlaybackAnchor
        val progress = resolveIssueSeekProgress(
            readySession = readySession,
            anchor = anchor,
            issueLine = issueLine
        )

        if (progress == null) {
            sendEffect(KmdReaderEffect.ShowMessage("这个问题还没有可跳转的播放锚点。"))
            return
        }

        viewModelScope.launch {
            runCatching {
                runtimeBridge.seek(progress.coerceIn(0f, 1f))
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("问题播放位置跳转失败"))
            }
        }
    }

    private fun jumpSelectedSourceLineToPlayback() {
        reduce(KmdReaderAction.JumpSelectedSourceLineToPlayback)
        val state = _state.value
        val selectedLine = state.issueFocus.selectedSourceLine
            ?: state.issueFocus.focusedSourceRange?.startLine
        if (selectedLine == null) {
            sendEffect(KmdReaderEffect.ShowMessage("请先在源码中选中一行。"))
            return
        }

        val readySession = state.readerSession as? ReaderSessionState.Ready
        if (readySession == null || readySession.workId != state.deskStack.currentWorkId) {
            sendEffect(KmdReaderEffect.ShowMessage("作品还没有加载完成，暂时不能跳转播放位置。"))
            return
        }

        val progress = resolveSourceLineSeekProgress(
            readySession = readySession,
            sourceLine = selectedLine
        )
        if (progress == null) {
            sendEffect(KmdReaderEffect.ShowMessage("选中行之后没有可播放的脚本段。"))
            return
        }

        viewModelScope.launch {
            runCatching {
                runtimeBridge.seek(progress.coerceIn(0f, 1f))
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("源码行播放位置跳转失败"))
            }
        }
    }

    private fun updateReaderHostSize(action: KmdReaderAction.UpdateReaderHostSize) {
        val previousViewport = _state.value.readerViewport
        if (
            previousViewport.hostWidthPx == action.widthPx &&
            previousViewport.hostHeightPx == action.heightPx
        ) {
            return
        }

        reduce(action)

        val session = _state.value.readerSession
        if (session !is ReaderSessionState.Ready) {
            return
        }

        viewModelScope.launch {
            runCatching {
                runtimeBridge.updateSettings(
                    ReaderViewportPolicy.settingsFor(_state.value.readerViewport)
                )
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("阅读画布适配失败"))
            }
        }
    }

    private fun requestRuntimeInspection(workId: String) {
        val session = _state.value.readerSession
        if (session !is ReaderSessionState.Ready || session.workId != workId) {
            return
        }

        viewModelScope.launch {
            runCatching {
                runtimeBridge.setInspectionEnabled(true)
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("Runtime 检查暂时不可用"))
            }
        }
    }

    private fun loadSourceSnapshot(workId: String) {
        val work = _state.value.works.firstOrNull { it.id == workId } ?: return

        viewModelScope.launch {
            runCatching {
                repository.getWorkSource(workId = workId, refresh = true)
                    ?: error("当前作品还没有可审阅的 KMD 源文本。")
            }.onSuccess { source ->
                val snapshot = KmdSourceSnapshot.fromSource(
                    workId = workId,
                    revisionId = work.script.activeRevisionId,
                    content = source,
                    fetchedAtMillis = nowMillis()
                )
                _state.update {
                    it.copy(
                        sourceSnapshotsByWorkId = it.sourceSnapshotsByWorkId + (
                            workId to snapshot
                            )
                    )
                }
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("源码快照暂时不可用"))
            }
        }
    }

    private fun disableRuntimeInspection() {
        val session = _state.value.readerSession
        if (session !is ReaderSessionState.Ready) {
            return
        }

        viewModelScope.launch {
            runCatching {
                runtimeBridge.setInspectionEnabled(false)
            }
        }
    }

    private fun refreshWorks(refresh: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingWorks = true, errorMessage = null) }
            runCatching {
                repository.listWorks(refresh = refresh)
            }.onSuccess { works ->
                _state.update {
                    it.copy(
                        works = works,
                        isLoadingWorks = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoadingWorks = false,
                        errorMessage = error.message ?: "加载作品列表失败"
                    )
                }
                sendEffect(KmdReaderEffect.ShowMessage("加载作品列表失败"))
            }
        }
    }

    private fun loadIssues(workId: String) {
        viewModelScope.launch {
            runCatching {
                repository.listIssues(workId = workId, refresh = true)
            }.onSuccess { issues ->
                _state.update {
                    it.copy(
                        issuesByWorkId = it.issuesByWorkId + (
                            workId to mergeIssues(
                                current = it.issuesByWorkId[workId].orEmpty(),
                                incoming = issues
                            )
                            )
                    )
                }
            }.onFailure {
                sendEffect(KmdReaderEffect.ShowMessage("脚本检查结果暂时不可用"))
            }
        }
    }

    private fun reduce(action: KmdReaderAction) {
        _state.update { KmdReaderReducer.reduce(it, action) }
    }

    private fun sendEffect(effect: KmdReaderEffect) {
        viewModelScope.launch {
            effects.send(effect)
        }
    }

    override fun onCleared() {
        // R3-B 步骤5：兜底落盘。viewModelScope 此时已取消，用 runBlocking 同步写最后一次进度。
        // 这是 Android ViewModel 落盘的标准做法；单次 Room 写 WAL 下 <1ms，窗口可接受。
        flushProgressOnCleared()
        // R3-C 步骤2 兜底：同步存一次当前草稿（防最后一字丢失）。
        flushDraftOnCleared()
        runtimeBridge.dispose()
        super.onCleared()
    }

    /**
     * R3-B 步骤5：onCleared 兜底落盘。抽出为 internal 以便单元测试直接驱动
     * （onCleared 是 protected，测试无法调用）。生产代码只在 onCleared 调用。
     */
    internal fun flushProgressOnCleared() {
        runCatching {
            runBlocking {
                val session = _state.value.readerSession as? ReaderSessionState.Ready
                    ?: return@runBlocking
                localLibrary.updateProgress(
                    workId = session.workId,
                    progress = session.progress,
                    timeMs = session.timeMs,
                    durationMs = session.durationMs,
                    now = nowMillis()
                )
            }
        }
    }

    class Factory(
        private val repository: WorkRepository,
        private val runtimeBridge: ReaderRuntimeBridge,
        private val localLibrary: LocalLibraryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(KmdReaderViewModel::class.java)) {
                return KmdReaderViewModel(repository, runtimeBridge, System::currentTimeMillis, localLibrary) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    private fun mergeIssues(
        current: List<ScriptIssue>,
        incoming: List<ScriptIssue>
    ): List<ScriptIssue> {
        return (current + incoming).distinctBy { it.id }
    }

    private fun resolveCurrentMarker(
        event: ReaderRuntimeEvent.ProgressChanged,
        readySession: ReaderSessionState.Ready?
    ): ReaderRuntimeTimelineMarker? {
        val markers = readySession?.timelineMarkers.orEmpty()
        if (markers.isEmpty()) {
            return null
        }

        event.markerId?.let { markerId ->
            markers.firstOrNull { it.id == markerId }?.let { return it }
        }

        event.timeMs
            ?.takeIf { it >= 0L }
            ?.let { timeMs ->
                markerAtTime(markers = markers, timeMs = timeMs)?.let { return it }
            }

        event.line?.let { line ->
            markers.firstOrNull { it.line == line }?.let { return it }
        }

        return null
    }

    private fun markerAtTime(
        markers: List<ReaderRuntimeTimelineMarker>,
        timeMs: Long
    ): ReaderRuntimeTimelineMarker? =
        markers
            .filter { marker ->
                val start = marker.timeMs ?: marker.startTimeMs
                start != null && start <= timeMs
            }
            .maxByOrNull { it.timeMs ?: it.startTimeMs ?: 0L }

    private fun resolveIssueSeekProgress(
        readySession: ReaderSessionState.Ready,
        anchor: PlaybackAnchor?,
        issueLine: Int?
    ): Float? {
        anchor?.progress?.let { return it }
        val duration = readySession.durationMs?.takeIf { it > 0L }
        if (duration != null) {
            anchor?.timeMs?.takeIf { it >= 0L }?.let { return it.toFloat() / duration }
        }
        anchor?.markerId
            ?.let { markerId ->
                readySession.timelineMarkers.firstOrNull { it.id == markerId }
            }
            ?.let { marker ->
                marker.progress?.let { return it }
                val markerTime = marker.timeMs ?: marker.startTimeMs
                if (duration != null && markerTime != null) {
                    return markerTime.toFloat() / duration
                }
            }
        issueLine
            ?.let { line -> markerAtOrAfterLine(readySession.timelineMarkers, line) }
            ?.let { marker ->
                marker.progress?.let { return it }
                val markerTime = marker.timeMs ?: marker.startTimeMs
                if (duration != null && markerTime != null) {
                    return markerTime.toFloat() / duration
                }
            }
        return null
    }

    private fun resolveSourceLineSeekProgress(
        readySession: ReaderSessionState.Ready,
        sourceLine: Int
    ): Float? {
        val marker = markerAtOrAfterLine(
            markers = readySession.timelineMarkers,
            lineNumber = sourceLine
        ) ?: return null
        marker.progress?.let { return it }

        val duration = readySession.durationMs?.takeIf { it > 0L } ?: return null
        val markerTime = marker.timeMs ?: marker.startTimeMs ?: return null
        return markerTime.toFloat() / duration
    }

    private fun markerAtOrAfterLine(
        markers: List<ReaderRuntimeTimelineMarker>,
        lineNumber: Int
    ): ReaderRuntimeTimelineMarker? =
        markers
            .filter { marker -> marker.line != null && marker.line >= lineNumber }
            .minWithOrNull(
                compareBy<ReaderRuntimeTimelineMarker> { it.line ?: Int.MAX_VALUE }
                    .thenBy { it.timeMs ?: it.startTimeMs ?: Long.MAX_VALUE }
            )

    private fun String.lineNumberHint(): Int? {
        val patterns = listOf(
            Regex("""(?:^|[:\s])line[:=#]?\s*(\d+)(?:$|[:\s])""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[:\s])L(\d+)(?:$|[:\s])""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun ReaderSessionState.workIdOrNull(): String? =
        when (this) {
            ReaderSessionState.Idle -> null
            is ReaderSessionState.Loading -> workId
            is ReaderSessionState.Ready -> workId
            is ReaderSessionState.Failed -> workId
        }

    private fun ReaderSessionState.capabilitiesOrNull() =
        when (this) {
            ReaderSessionState.Idle -> null
            is ReaderSessionState.Loading -> capabilities
            is ReaderSessionState.Ready -> capabilities
            is ReaderSessionState.Failed -> capabilities
        }

    private fun ReaderSessionState.sessionIdOrNull(): String? =
        when (this) {
            ReaderSessionState.Idle -> null
            is ReaderSessionState.Loading -> sessionId
            is ReaderSessionState.Ready -> sessionId
            is ReaderSessionState.Failed -> sessionId
        }

    private fun ReaderSessionState.phaseOrNull(): ReaderSessionPhase? =
        when (this) {
            ReaderSessionState.Idle -> null
            is ReaderSessionState.Loading -> phase
            is ReaderSessionState.Ready -> ReaderSessionPhase.WorkLoading
            is ReaderSessionState.Failed -> phase
        }

    private fun ReaderSessionState.isFailedFor(workId: String): Boolean =
        this is ReaderSessionState.Failed && this.workId == workId

    // R3-B 步骤2：Work → LocalLibraryEntry。首次阅读时建条目，进度=0、不上架，
    // 时间字段留空（进度由 ProgressChanged/onCleared 写入）。
    private fun com.example.kmd_reader.domain.model.Work.toLocalLibraryEntry(): LocalLibraryEntry =
        LocalLibraryEntry(
            workId = id,
            source = sourceType,
            onShelf = false,
            title = title,
            authorName = authorName,
            presentationMode = presentation.mode,
            aspectRatio = presentation.aspectRatio,
            kmdSource = null,
            contentUri = contentUri,
            readingProgress = 0f,
            readingTimeMs = null,
            readingDurationMs = null,
            lastReadAt = null,
            importedAt = null,
            cachedAt = null
        )
}
