package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.IssueSeverity
import com.example.kmd_reader.domain.model.KmdSourceRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

@Serializable
data class PlaybackAnchor(
    val timeMs: Long? = null,
    val progress: Float? = null,
    val line: Int? = null,
    val markerId: String? = null
)

/**
 * R3-C：issue 草稿。`id` 是持久化主键（"draft-{uuid}"），在起草时生成。
 * 其余字段随用户编辑/锚点采集而变。序列化用于写入 local_drafts 的 payload。
 */
@Serializable
data class IssueDraft(
    val id: String = "",
    val workId: String,
    val revisionId: String,
    val sourceRange: KmdSourceRange? = null,
    val playbackAnchor: PlaybackAnchor? = null,
    val severity: IssueSeverity = IssueSeverity.Warning,
    val message: String = "",
    val suggestion: String = ""
) {
    companion object {
        // ignoreUnknownKeys：容忍未来新增字段（向前兼容）；旧版本读新版草稿不会崩。
        internal val json = Json { ignoreUnknownKeys = true }
    }
}

/** R3-C：IssueDraft ↔ JSON 编解码，用于 local_drafts.payload 透传。 */
fun IssueDraft.toJson(): String = IssueDraft.json.encodeToString(this)

fun issueDraftFromJson(payload: String): IssueDraft = IssueDraft.json.decodeFromString(payload)

data class IssueStatusOverride(
    val status: IssueLocalStatus = IssueLocalStatus.Open,
    val reason: String? = null,
    val note: String? = null
)

enum class IssueLocalStatus(val label: String) {
    Open("打开"),
    Closed("已关闭")
}
