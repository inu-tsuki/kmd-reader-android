package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.KmdSourceRange
import com.example.kmd_reader.domain.policy.DeskStackPolicy
import com.example.kmd_reader.domain.policy.ReaderViewportPolicy

object KmdReaderReducer {
    fun reduce(state: KmdReaderState, action: KmdReaderAction): KmdReaderState =
        when (action) {
            KmdReaderAction.RefreshWorks -> state

            KmdReaderAction.OpenMine -> state.copy(
                deskStack = DeskStackPolicy.openMine(state.deskStack)
            )

            KmdReaderAction.OpenBrowse -> state.copy(
                deskStack = DeskStackPolicy.openBrowse(state.deskStack)
            )

            is KmdReaderAction.OpenWork -> {
                val nextDeskStack = DeskStackPolicy.openWork(state.deskStack, action.workId)
                val nextWork = state.works.firstOrNull { it.id == action.workId }
                state.copy(
                    deskStack = nextDeskStack,
                    issueFocus = IssueFocusState(
                        issueStatusOverrides = state.issueFocus.issueStatusOverrides,
                        playbackAnchorsByIssueId = state.issueFocus.playbackAnchorsByIssueId
                    ),
                    readerViewport = nextWork?.let { work ->
                        ReaderViewportPolicy.resolve(
                            work = work,
                            hostWidthPx = state.readerViewport.hostWidthPx,
                            hostHeightPx = state.readerViewport.hostHeightPx
                        )
                    } ?: state.readerViewport.copy(sourceHints = null)
                )
            }

            KmdReaderAction.OpenReader -> state.copy(
                deskStack = DeskStackPolicy.openReader(state.deskStack),
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reading),
                readerViewport = state.selectedWork?.let { work ->
                    ReaderViewportPolicy.resolve(
                        work = work,
                        hostWidthPx = state.readerViewport.hostWidthPx,
                        hostHeightPx = state.readerViewport.hostHeightPx,
                        sourceHints = state.readerViewport.sourceHints
                    )
                } ?: state.readerViewport
            )

            KmdReaderAction.PlayReader -> state

            KmdReaderAction.PauseReader -> state.copy(
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reading)
            )

            is KmdReaderAction.SeekReader -> state.copy(
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reading)
            )

            KmdReaderAction.RetryReaderRuntime -> state.copy(
                readerHostRestartToken = state.readerHostRestartToken + 1,
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reading),
                readerViewport = state.selectedWork?.let { work ->
                    ReaderViewportPolicy.resolve(
                        work = work,
                        hostWidthPx = state.readerViewport.hostWidthPx,
                        hostHeightPx = state.readerViewport.hostHeightPx,
                        sourceHints = state.readerViewport.sourceHints
                    )
                } ?: state.readerViewport
            )

            is KmdReaderAction.UpdateReaderHostSize -> state.copy(
                readerViewport = state.selectedWork?.let { work ->
                    ReaderViewportPolicy.resolve(
                        work = work,
                        hostWidthPx = action.widthPx,
                        hostHeightPx = action.heightPx,
                        sourceHints = state.readerViewport.sourceHints
                    )
                } ?: state.readerViewport.copy(
                    hostWidthPx = action.widthPx,
                    hostHeightPx = action.heightPx
                )
            )

            is KmdReaderAction.ReaderInteraction -> state.copy(
                readerChrome = state.readerChrome.show(atMillis = action.atMillis)
            )

            is KmdReaderAction.ToggleReaderChrome -> state.copy(
                readerChrome = state.readerChrome.toggle(atMillis = action.atMillis)
            )

            is KmdReaderAction.ShowReaderChrome -> state.copy(
                readerChrome = state.readerChrome.show(atMillis = action.atMillis)
            )

            KmdReaderAction.DimReaderChrome -> state.copy(
                readerChrome = state.readerChrome.dim()
            )

            KmdReaderAction.HideReaderChrome -> state.copy(
                readerChrome = state.readerChrome.hide()
            )

            is KmdReaderAction.SetReaderChromePinned -> state.copy(
                readerChrome = state.readerChrome.copy(
                    isPinned = action.pinned,
                    visibility = if (action.pinned) {
                        ReaderChromeVisibility.Visible
                    } else {
                        state.readerChrome.visibility
                    }
                )
            )

            is KmdReaderAction.OpenReaderCompanion -> state.copy(
                deskStack = if (action.type.isReviewTool) {
                    DeskStackPolicy.openReview(state.deskStack)
                } else {
                    state.deskStack
                },
                readerCompanion = state.readerCompanion.open(
                    type = action.type,
                    placement = action.placement
                ),
                readerChrome = state.readerChrome.show(
                    mode = if (action.type.isReviewTool) {
                        ReaderChromeMode.Reviewing
                    } else {
                        state.readerChrome.mode
                    }
                )
            )

            KmdReaderAction.CloseReaderCompanion -> state.copy(
                deskStack = if (state.readerCompanion.active?.isReviewTool == true) {
                    DeskStackPolicy.closeReview(state.deskStack)
                } else {
                    state.deskStack
                },
                readerCompanion = state.readerCompanion.close(),
                readerChrome = if (
                    state.readerCompanion.active?.isReviewTool == true ||
                    state.readerChrome.mode == ReaderChromeMode.Reviewing
                ) {
                    state.readerChrome.show(mode = ReaderChromeMode.Reading)
                } else {
                    state.readerChrome
                }
            )

            KmdReaderAction.ToggleReaderCompanionExpanded -> state.copy(
                readerCompanion = state.readerCompanion.toggleExpanded()
            )

            KmdReaderAction.OpenImport -> state.copy(
                deskStack = DeskStackPolicy.openImport(state.deskStack)
            )

            KmdReaderAction.CloseCurrentDesk -> state.copy(
                deskStack = DeskStackPolicy.closeCurrentDesk(state.deskStack)
            )

            is KmdReaderAction.SetActiveDesk -> state.copy(
                deskStack = DeskStackPolicy.setActive(state.deskStack, action.index)
            )

            KmdReaderAction.OpenSearch -> state.copy(
                deskStack = DeskStackPolicy.openSearch(state.deskStack)
            )

            KmdReaderAction.CloseSearch -> state.copy(
                deskStack = DeskStackPolicy.closeSearch(state.deskStack)
            )

            is KmdReaderAction.UpdateQuery -> state.copy(searchQuery = action.query)

            is KmdReaderAction.ToggleMode -> state.copy(
                selectedMode = if (state.selectedMode == action.mode) null else action.mode
            )

            KmdReaderAction.OpenReview -> state.copy(
                deskStack = DeskStackPolicy.openReview(state.deskStack),
                readerCompanion = state.readerCompanion.open(ReaderCompanionType.Review),
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reviewing)
            )

            KmdReaderAction.CloseReview -> state.copy(
                deskStack = DeskStackPolicy.closeReview(state.deskStack),
                readerCompanion = state.readerCompanion.close(),
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reading)
            )

            is KmdReaderAction.SetReviewMessage -> state.copy(
                deskStack = DeskStackPolicy.setReviewMessage(state.deskStack, action.message)
            )

            is KmdReaderAction.SelectIssue -> selectIssue(
                state = state,
                issueId = action.issueId
            )

            is KmdReaderAction.SelectSourceLine -> state.copy(
                issueFocus = state.issueFocus.copy(
                    selectedSourceLine = action.lineNumber,
                    focusedPlaybackAnchor = null,
                    issueDraft = null
                ),
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reviewing)
            )

            KmdReaderAction.ClearIssueFocus -> state.copy(
                issueFocus = state.issueFocus.copy(
                    selectedIssueId = null,
                    selectedSourceLine = null,
                    focusedSourceRange = null,
                    focusedPlaybackAnchor = null,
                    issueDraft = null
                )
            )

            KmdReaderAction.StartIssueDraftFromPlayback -> startIssueDraftFromPlayback(state)

            is KmdReaderAction.UpdateIssueDraftMessage -> state.copy(
                issueFocus = state.issueFocus.copy(
                    issueDraft = state.issueFocus.issueDraft?.copy(message = action.message)
                )
            )

            is KmdReaderAction.UpdateIssueDraftSuggestion -> state.copy(
                issueFocus = state.issueFocus.copy(
                    issueDraft = state.issueFocus.issueDraft?.copy(suggestion = action.suggestion)
                )
            )

            // R3-C：VM 生成 UUID 后回填 id（reducer 不调 UUID，保持纯函数）。
            is KmdReaderAction.AssignIssueDraftId -> state.copy(
                issueFocus = state.issueFocus.copy(
                    issueDraft = state.issueFocus.issueDraft?.copy(id = action.id)
                )
            )

            // R3-C：从 local_drafts 恢复 message/suggestion/severity。
            // 不恢复 sourceRange/playbackAnchor——每次 StartIssueDraft 重新采集锚点更安全。
            is KmdReaderAction.UpdateIssueDraftFromPersisted -> state.copy(
                issueFocus = state.issueFocus.copy(
                    issueDraft = state.issueFocus.issueDraft?.copy(
                        message = action.message,
                        suggestion = action.suggestion,
                        severity = action.severity
                    )
                )
            )

            KmdReaderAction.SubmitIssueDraft -> state

            KmdReaderAction.CancelIssueDraft -> state.copy(
                issueFocus = state.issueFocus.copy(issueDraft = null)
            )

            is KmdReaderAction.CloseIssue -> state.copy(
                issueFocus = state.issueFocus.copy(
                    selectedIssueId = action.issueId,
                    issueStatusOverrides = state.issueFocus.issueStatusOverrides + (
                        action.issueId to IssueStatusOverride(
                            status = IssueLocalStatus.Closed,
                            reason = action.reason
                        )
                        )
                )
            )

            is KmdReaderAction.ReopenIssue -> state.copy(
                issueFocus = state.issueFocus.copy(
                    selectedIssueId = action.issueId,
                    issueStatusOverrides = state.issueFocus.issueStatusOverrides + (
                        action.issueId to IssueStatusOverride(
                            status = IssueLocalStatus.Open,
                            reason = "reopened"
                        )
                        )
                )
            )

            is KmdReaderAction.JumpIssueToPlayback -> selectIssue(
                state = state,
                issueId = action.issueId
            ).copy(
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reviewing)
            )

            KmdReaderAction.JumpSelectedSourceLineToPlayback -> state.copy(
                readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reviewing)
            )
        }

    private fun selectIssue(
        state: KmdReaderState,
        issueId: String
    ): KmdReaderState {
        val workId = state.deskStack.currentWorkId
        val issue = workId
            ?.let { state.issuesByWorkId[it] }
            ?.firstOrNull { it.id == issueId }
            ?: state.issuesByWorkId.values.flatten().firstOrNull { it.id == issueId }
            ?: return state

        val snapshot = state.sourceSnapshotsByWorkId[issue.workId]
        val lineNumber = snapshot?.lineNumberForIssue(issue)
        val sourceRange = lineNumber?.let(::KmdSourceRange)
        val playbackAnchor = state.issueFocus.playbackAnchorsByIssueId[issueId]

        return state.copy(
            issueFocus = state.issueFocus.copy(
                selectedIssueId = issueId,
                selectedSourceLine = lineNumber,
                focusedSourceRange = sourceRange,
                focusedPlaybackAnchor = playbackAnchor,
                issueDraft = null
            )
        )
    }

    private fun startIssueDraftFromPlayback(state: KmdReaderState): KmdReaderState {
        val work = state.selectedWork ?: return state
        val ready = state.readerSession as? ReaderSessionState.Ready
        val selectedRange = state.issueFocus.selectedSourceLine?.let(::KmdSourceRange)
        val playbackRange = ready
            ?.takeIf { it.workId == work.id }
            ?.currentLine
            ?.let(::KmdSourceRange)
        val sourceRange = selectedRange ?: playbackRange
        val playbackAnchor = ready
            ?.takeIf { it.workId == work.id }
            ?.let {
                PlaybackAnchor(
                    timeMs = it.timeMs,
                    progress = it.progress,
                    line = it.currentLine,
                    markerId = it.currentMarkerId
                )
            }

        return state.copy(
            issueFocus = state.issueFocus.copy(
                selectedIssueId = null,
                selectedSourceLine = sourceRange?.startLine,
                focusedSourceRange = sourceRange,
                focusedPlaybackAnchor = playbackAnchor,
                issueDraft = IssueDraft(
                    workId = work.id,
                    revisionId = state.sourceSnapshotsByWorkId[work.id]?.revisionId
                        ?: work.script.activeRevisionId,
                    sourceRange = sourceRange,
                    playbackAnchor = playbackAnchor,
                    message = sourceRange?.let { "在 ${it.lineLabel} 需要确认表现。" }.orEmpty(),
                    suggestion = "请补充期望表现或修改建议。"
                )
            ),
            readerChrome = state.readerChrome.show(mode = ReaderChromeMode.Reviewing)
        )
    }

    private val ReaderCompanionType.isReviewTool: Boolean
        get() = this == ReaderCompanionType.Review || this == ReaderCompanionType.Issues
}
