package com.example.kmd_reader.domain.model

data class ScriptIssue(
    val id: String,
    val workId: String,
    val severity: IssueSeverity,
    val source: IssueSource,
    val location: String,
    val message: String,
    val suggestion: String
)

enum class IssueSeverity(val label: String) {
    Info("提示"),
    Warning("警告"),
    Error("错误")
}

enum class IssueSource(val label: String) {
    Parser("Parser"),
    Layout("Layout"),
    Effect("Effect"),
    Asset("Asset"),
    Performance("Performance")
}
