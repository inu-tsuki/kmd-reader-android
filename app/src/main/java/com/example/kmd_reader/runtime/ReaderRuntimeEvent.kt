package com.example.kmd_reader.runtime

import com.example.kmd_reader.domain.model.ScriptIssue

sealed interface ReaderRuntimeEvent {
    data class TransportReady(
        val runtime: String,
        val version: Int,
        val capabilities: ReaderRuntimeCapabilities? = null,
        val sessionId: String? = null
    ) : ReaderRuntimeEvent

    data class Ready(
        val workId: String,
        val durationMs: Long? = null,
        val timelineMarkers: List<ReaderRuntimeTimelineMarker> = emptyList(),
        val sessionId: String? = null
    ) : ReaderRuntimeEvent

    data class ProgressChanged(
        val workId: String,
        val progress: Float,
        val positionPayload: String,
        val timeMs: Long? = null,
        val durationMs: Long? = null,
        val segmentId: String? = null,
        val paragraphIndex: Int? = null,
        val line: Int? = null,
        val checkpointId: String? = null,
        val markerId: String? = null,
        val sessionId: String? = null
    ) : ReaderRuntimeEvent

    data class PlaybackStateChanged(
        val workId: String,
        val isPlaying: Boolean,
        val state: String? = null,
        val sessionId: String? = null
    ) : ReaderRuntimeEvent

    data class InspectionReported(
        val workId: String,
        val issues: List<ScriptIssue>,
        val sessionId: String? = null
    ) : ReaderRuntimeEvent

    data class Failed(
        val workId: String?,
        val message: String,
        val code: String? = null,
        val commandId: String? = null,
        val recoverable: Boolean? = null,
        val sessionId: String? = null
    ) : ReaderRuntimeEvent
}
