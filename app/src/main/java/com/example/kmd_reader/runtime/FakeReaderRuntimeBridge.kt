package com.example.kmd_reader.runtime

import com.example.kmd_reader.domain.model.IssueSeverity
import com.example.kmd_reader.domain.model.IssueSource
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeReaderRuntimeBridge : ReaderRuntimeBridge {
    private val _events = MutableSharedFlow<ReaderRuntimeEvent>(
        extraBufferCapacity = 16
    )
    override val events: SharedFlow<ReaderRuntimeEvent> = _events.asSharedFlow()

    private var currentWork: Work? = null
    private var currentProgress: Float = 0f
    private var isPlaying: Boolean = false

    override suspend fun attach() = Unit

    override suspend fun load(request: ReaderLoadRequest) {
        currentWork = request.work
        currentProgress = 0.42f
        isPlaying = false

        _events.emit(ReaderRuntimeEvent.Ready(workId = request.work.id))
        _events.emit(
            ReaderRuntimeEvent.ProgressChanged(
                workId = request.work.id,
                progress = currentProgress,
                positionPayload = "fake:${request.work.id}:0.42"
            )
        )
    }

    override suspend fun play() {
        val workId = currentWork?.id ?: return
        isPlaying = true
        _events.emit(
            ReaderRuntimeEvent.PlaybackStateChanged(
                workId = workId,
                isPlaying = true
            )
        )
    }

    override suspend fun pause() {
        val workId = currentWork?.id ?: return
        isPlaying = false
        _events.emit(
            ReaderRuntimeEvent.PlaybackStateChanged(
                workId = workId,
                isPlaying = false
            )
        )
    }

    override suspend fun seek(progress: Float) {
        val workId = currentWork?.id ?: return
        currentProgress = progress.coerceIn(0f, 1f)
        _events.emit(
            ReaderRuntimeEvent.ProgressChanged(
                workId = workId,
                progress = currentProgress,
                positionPayload = "fake:$workId:$currentProgress"
            )
        )
    }

    override suspend fun setInspectionEnabled(enabled: Boolean) {
        val work = currentWork ?: return
        if (!enabled) {
            return
        }

        _events.emit(
            ReaderRuntimeEvent.InspectionReported(
                workId = work.id,
                issues = listOf(createFakeIssue(work))
            )
        )
    }

    override suspend fun updateSettings(settings: ReaderSettings) = Unit

    override fun dispose() {
        currentWork = null
        currentProgress = 0f
        isPlaying = false
    }

    private fun createFakeIssue(work: Work): ScriptIssue {
        return ScriptIssue(
            id = "runtime-${work.id}-inspection",
            workId = work.id,
            severity = IssueSeverity.Info,
            source = IssueSource.Runtime,
            location = "reader-runtime",
            message = "Fake Runtime 已完成一次移动端检查。",
            suggestion = "真实 WebView Runtime 接入后，这里会显示解析、布局和播放阶段回传的问题。"
        )
    }
}
