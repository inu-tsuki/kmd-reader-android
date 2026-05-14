package com.example.kmd_reader.runtime

import com.example.kmd_reader.domain.model.ScriptIssue

sealed interface ReaderRuntimeEvent {
    data class Ready(
        val workId: String
    ) : ReaderRuntimeEvent

    data class ProgressChanged(
        val workId: String,
        val progress: Float,
        val positionPayload: String
    ) : ReaderRuntimeEvent

    data class PlaybackStateChanged(
        val workId: String,
        val isPlaying: Boolean
    ) : ReaderRuntimeEvent

    data class InspectionReported(
        val workId: String,
        val issues: List<ScriptIssue>
    ) : ReaderRuntimeEvent

    data class Failed(
        val workId: String?,
        val message: String
    ) : ReaderRuntimeEvent
}
