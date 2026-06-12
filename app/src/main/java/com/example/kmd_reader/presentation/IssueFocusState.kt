package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.IssueSeverity
import com.example.kmd_reader.domain.model.KmdSourceRange

data class IssueFocusState(
    val selectedIssueId: String? = null,
    val selectedSourceLine: Int? = null,
    val focusedSourceRange: KmdSourceRange? = null,
    val focusedPlaybackAnchor: PlaybackAnchor? = null,
    val issueDraft: IssueDraft? = null,
    val issueStatusOverrides: Map<String, IssueStatusOverride> = emptyMap(),
    val playbackAnchorsByIssueId: Map<String, PlaybackAnchor> = emptyMap()
) {
    fun statusFor(issueId: String): IssueStatusOverride =
        issueStatusOverrides[issueId] ?: IssueStatusOverride()
}

data class PlaybackAnchor(
    val timeMs: Long? = null,
    val progress: Float? = null,
    val line: Int? = null,
    val markerId: String? = null
)

data class IssueDraft(
    val workId: String,
    val revisionId: String,
    val sourceRange: KmdSourceRange? = null,
    val playbackAnchor: PlaybackAnchor? = null,
    val severity: IssueSeverity = IssueSeverity.Warning,
    val message: String = "",
    val suggestion: String = ""
)

data class IssueStatusOverride(
    val status: IssueLocalStatus = IssueLocalStatus.Open,
    val reason: String? = null,
    val note: String? = null
)

enum class IssueLocalStatus(val label: String) {
    Open("打开"),
    Closed("已关闭")
}
