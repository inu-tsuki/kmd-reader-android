package com.example.kmd_reader.presentation

import com.example.kmd_reader.runtime.ReaderRuntimeCapabilities
import com.example.kmd_reader.runtime.ReaderRuntimeTimelineMarker

sealed interface ReaderSessionState {
    data object Idle : ReaderSessionState

    data class Loading(
        val workId: String,
        val phase: ReaderSessionPhase = ReaderSessionPhase.RuntimeLoading,
        val runtimeName: String? = null,
        val capabilities: ReaderRuntimeCapabilities? = null,
        val sessionId: String? = null
    ) : ReaderSessionState

    data class Ready(
        val workId: String,
        val progress: Float,
        val isPlaying: Boolean,
        val playbackState: String? = null,
        val timeMs: Long? = null,
        val durationMs: Long? = null,
        val currentLine: Int? = null,
        val currentMarkerId: String? = null,
        val timelineMarkers: List<ReaderRuntimeTimelineMarker> = emptyList(),
        val capabilities: ReaderRuntimeCapabilities? = null,
        val sessionId: String? = null
    ) : ReaderSessionState

    data class Failed(
        val workId: String,
        val message: String,
        val phase: ReaderSessionPhase? = null,
        val code: String? = null,
        val commandId: String? = null,
        val recoverable: Boolean? = null,
        val sessionId: String? = null,
        val capabilities: ReaderRuntimeCapabilities? = null,
        val diagnostics: String? = null
    ) : ReaderSessionState
}

enum class ReaderSessionPhase(val label: String) {
    RuntimeLoading("等待 Runtime 连接"),
    TransportReady("Runtime 已连接"),
    WorkLoading("作品加载中")
}
