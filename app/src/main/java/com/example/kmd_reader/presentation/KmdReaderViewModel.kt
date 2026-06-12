package com.example.kmd_reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kmd_reader.data.MockWorkRepository
import com.example.kmd_reader.data.WorkRepository
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

class KmdReaderViewModel(
    private val repository: WorkRepository = MockWorkRepository(),
    private val runtimeBridge: ReaderRuntimeBridge = FakeReaderRuntimeBridge(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {
    val runtimeBridgeForHost: ReaderRuntimeBridge
        get() = runtimeBridge

    private val _state = MutableStateFlow(KmdReaderState())
    val state: StateFlow<KmdReaderState> = _state.asStateFlow()

    private val effects = Channel<KmdReaderEffect>(capacity = Channel.BUFFERED)
    val effectFlow = effects.receiveAsFlow()

    init {
        observeRuntimeEvents()
        refreshWorks()
    }

    fun onAction(action: KmdReaderAction) {
        when (action) {
            KmdReaderAction.RefreshWorks -> refreshWorks()
            is KmdReaderAction.OpenWork -> {
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
                _state.update {
                    if (it.readerSession.isFailedFor(event.workId)) {
                        return@update it
                    }
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
            }
            is ReaderRuntimeEvent.ProgressChanged -> {
                val currentSession = _state.value.readerSession
                if (currentSession.isFailedFor(event.workId)) {
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
            }
            is ReaderRuntimeEvent.PlaybackStateChanged -> {
                val currentSession = _state.value.readerSession
                if (currentSession.isFailedFor(event.workId)) {
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
                _state.update {
                    val currentSession = it.readerSession
                    it.copy(
                        readerSession = ReaderSessionState.Failed(
                            workId = event.workId ?: it.deskStack.currentWorkId.orEmpty(),
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

    private fun submitIssueDraft() {
        val state = _state.value
        val draft = state.issueFocus.issueDraft ?: return
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
        runtimeBridge.dispose()
        super.onCleared()
    }

    class Factory(
        private val repository: WorkRepository,
        private val runtimeBridge: ReaderRuntimeBridge
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(KmdReaderViewModel::class.java)) {
                return KmdReaderViewModel(repository, runtimeBridge) as T
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
}
