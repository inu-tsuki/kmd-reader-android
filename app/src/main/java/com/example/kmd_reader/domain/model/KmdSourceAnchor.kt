package com.example.kmd_reader.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class KmdSourceRange(
    val startLine: Int,
    val startColumn: Int? = null,
    val endLine: Int = startLine,
    val endColumn: Int? = null
) {
    val lineLabel: String
        get() = if (startLine == endLine) {
            "L$startLine"
        } else {
            "L$startLine-L$endLine"
        }
}

data class KmdSourceHighlight(
    val range: KmdSourceRange,
    val kind: KmdSourceHighlightKind,
    val label: String? = null
)

enum class KmdSourceHighlightKind {
    Playback,
    Issue,
    Selection,
    Discussion,
    Search
}
