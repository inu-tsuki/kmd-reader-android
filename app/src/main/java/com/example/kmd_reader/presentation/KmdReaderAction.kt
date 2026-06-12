package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.PresentationMode

sealed interface KmdReaderAction {
    data object RefreshWorks : KmdReaderAction
    data object OpenMine : KmdReaderAction
    data object OpenBrowse : KmdReaderAction
    data class OpenWork(val workId: String) : KmdReaderAction
    data object OpenReader : KmdReaderAction
    data object PlayReader : KmdReaderAction
    data object PauseReader : KmdReaderAction
    data class SeekReader(val progress: Float) : KmdReaderAction
    data object RetryReaderRuntime : KmdReaderAction
    data class UpdateReaderHostSize(val widthPx: Int, val heightPx: Int) : KmdReaderAction
    data class ReaderInteraction(val atMillis: Long = System.currentTimeMillis()) : KmdReaderAction
    data class ToggleReaderChrome(val atMillis: Long = System.currentTimeMillis()) : KmdReaderAction
    data class ShowReaderChrome(val atMillis: Long = System.currentTimeMillis()) : KmdReaderAction
    data object DimReaderChrome : KmdReaderAction
    data object HideReaderChrome : KmdReaderAction
    data class SetReaderChromePinned(val pinned: Boolean) : KmdReaderAction
    data class OpenReaderCompanion(
        val type: ReaderCompanionType,
        val placement: ReaderCompanionPlacement? = null
    ) : KmdReaderAction
    data object CloseReaderCompanion : KmdReaderAction
    data object ToggleReaderCompanionExpanded : KmdReaderAction
    data object OpenImport : KmdReaderAction
    data object CloseCurrentDesk : KmdReaderAction
    data class SetActiveDesk(val index: Int) : KmdReaderAction
    data object OpenSearch : KmdReaderAction
    data object CloseSearch : KmdReaderAction
    data class UpdateQuery(val query: String) : KmdReaderAction
    data class ToggleMode(val mode: PresentationMode) : KmdReaderAction
    data object OpenReview : KmdReaderAction
    data object CloseReview : KmdReaderAction
    data class SetReviewMessage(val message: String) : KmdReaderAction
    data class SelectIssue(val issueId: String) : KmdReaderAction
    data class SelectSourceLine(val lineNumber: Int) : KmdReaderAction
    data object ClearIssueFocus : KmdReaderAction
    data object StartIssueDraftFromPlayback : KmdReaderAction
    data class UpdateIssueDraftMessage(val message: String) : KmdReaderAction
    data class UpdateIssueDraftSuggestion(val suggestion: String) : KmdReaderAction
    data object SubmitIssueDraft : KmdReaderAction
    data object CancelIssueDraft : KmdReaderAction
    data class CloseIssue(val issueId: String, val reason: String = "fixed") : KmdReaderAction
    data class ReopenIssue(val issueId: String) : KmdReaderAction
    data class JumpIssueToPlayback(val issueId: String) : KmdReaderAction
    data object JumpSelectedSourceLineToPlayback : KmdReaderAction
}
