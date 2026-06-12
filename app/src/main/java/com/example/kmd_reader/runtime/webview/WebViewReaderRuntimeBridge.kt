package com.example.kmd_reader.runtime.webview

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import com.example.kmd_reader.runtime.ReaderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class WebViewReaderRuntimeBridge : ReaderRuntimeBridge {
    private companion object {
        const val LogTag = "KmdReaderWebView"
    }

    private val _events = MutableSharedFlow<ReaderRuntimeEvent>(
        extraBufferCapacity = 32
    )
    override val events: Flow<ReaderRuntimeEvent> = _events.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCommands = ArrayDeque<String>()
    private val traceEntries = ArrayDeque<String>()

    private var webView: WebView? = null
    private var runtimeReady = false
    private var requestedWorkId: String? = null
    private var currentWorkId: String? = null
    private var lastLoadRequest: ReaderLoadRequest? = null
    private var nextMessageNumber = 0
    private var lastProgressTraceAtMs = 0L

    override suspend fun attach() = Unit

    override fun prepareLoad(workId: String) {
        synchronized(this) {
            requestedWorkId = workId
            pendingCommands.clear()
            lastLoadRequest = null
            currentWorkId = workId
            traceLocked("prepare load work=$workId pending=${pendingCommands.size}")
        }
    }

    override suspend fun load(request: ReaderLoadRequest) {
        synchronized(this) {
            requestedWorkId = request.work.id
            currentWorkId = request.work.id
            lastLoadRequest = request
        }
        trace(
            "load work=${request.work.id} sourceChars=${request.source?.length ?: 0} " +
                "sourceUrl=${request.sourceUrl ?: "-"} fonts=${request.assetManifest?.fonts?.size ?: 0}"
        )
        sendCommand {
            RuntimeMessageCodec.encodeLoadScript(
                request = request,
                id = nextMessageIdLocked()
            )
        }
    }

    override suspend fun play() {
        trace("command play work=${synchronized(this) { currentWorkId ?: "-" }}")
        sendCommand {
            RuntimeMessageCodec.encodePlay(id = nextMessageIdLocked())
        }
    }

    override suspend fun pause() {
        trace("command pause work=${synchronized(this) { currentWorkId ?: "-" }}")
        sendCommand {
            RuntimeMessageCodec.encodePause(id = nextMessageIdLocked())
        }
    }

    override suspend fun seek(progress: Float) {
        trace("command seek progress=$progress work=${synchronized(this) { currentWorkId ?: "-" }}")
        sendCommand {
            RuntimeMessageCodec.encodeSeek(
                progress = progress,
                id = nextMessageIdLocked()
            )
        }
    }

    override suspend fun setInspectionEnabled(enabled: Boolean) {
        trace("command inspection enabled=$enabled work=${synchronized(this) { currentWorkId ?: "-" }}")
        sendCommand {
            RuntimeMessageCodec.encodeSetInspectionEnabled(
                enabled = enabled,
                id = nextMessageIdLocked()
            )
        }
    }

    override suspend fun updateSettings(settings: ReaderSettings) {
        trace("command settings viewport=${settings.viewport?.width}x${settings.viewport?.height}")
        sendCommand {
            RuntimeMessageCodec.encodeUpdateSettings(
                settings = settings,
                id = nextMessageIdLocked()
            )
        }
    }

    fun bind(webView: WebView) {
        synchronized(this) {
            this.webView = webView
            runtimeReady = false
            traceLocked("bind webView=${System.identityHashCode(webView)} pending=${pendingCommands.size}")
            if (pendingCommands.isEmpty()) {
                lastLoadRequest
                    ?.takeIf { it.work.id == requestedWorkId }
                    ?.let { request ->
                        pendingCommands.addLast(
                            RuntimeMessageCodec.encodeLoadScript(
                                request = request,
                                id = nextMessageIdLocked()
                            )
                        )
                    }
            }
        }
    }

    fun unbind(webView: WebView) {
        synchronized(this) {
            if (this.webView === webView) {
                traceLocked("unbind webView=${System.identityHashCode(webView)}")
                this.webView = null
                runtimeReady = false
            }
        }
    }

    fun receiveFromRuntime(rawMessage: String) {
        when (val message = RuntimeMessageCodec.decodeInbound(rawMessage)) {
            is RuntimeInboundMessage.HostReady -> {
                synchronized(this) {
                    runtimeReady = true
                    traceRuntimeEventLocked(message.event)
                }
                _events.tryEmit(message.event)
                flushPendingCommands()
            }
            is RuntimeInboundMessage.Event -> {
                synchronized(this) {
                    if (message.event is ReaderRuntimeEvent.Ready) {
                        currentWorkId = message.event.workId
                    }
                    traceRuntimeEventLocked(message.event)
                }
                _events.tryEmit(message.event)
            }
            is RuntimeInboundMessage.Invalid -> {
                reportHostError(message.message)
            }
            is RuntimeInboundMessage.Unknown -> Unit
        }
    }

    fun reportHostError(message: String) {
        trace("hostError $message")
        _events.tryEmit(
            ReaderRuntimeEvent.Failed(
                workId = synchronized(this) { currentWorkId },
                message = message
            )
        )
    }

    override fun dispose() {
        val disposeCommand = synchronized(this) {
            traceLocked("dispose")
            RuntimeMessageCodec.encodeDispose(id = nextMessageIdLocked())
        }
        sendCommand { disposeCommand }
        synchronized(this) {
            pendingCommands.clear()
            requestedWorkId = null
            currentWorkId = null
            lastLoadRequest = null
            runtimeReady = false
        }
    }

    override fun debugSnapshot(): String = synchronized(this) {
        buildString {
            appendLine("workId=${currentWorkId ?: "-"}")
            appendLine("runtimeReady=$runtimeReady")
            appendLine("pendingCommands=${pendingCommands.size}")
            appendLine("lastLoadWork=${lastLoadRequest?.work?.id ?: "-"}")
            appendLine("webViewBound=${webView != null}")
            appendLine("recentBridgeTrace:")
            if (traceEntries.isEmpty()) {
                appendLine("  <empty>")
            } else {
                traceEntries.forEach { entry ->
                    appendLine("  $entry")
                }
            }
        }
    }

    private fun sendCommand(commandFactory: WebViewReaderRuntimeBridge.() -> String) {
        val command = synchronized(this) {
            commandFactory()
        }
        val target = synchronized(this) {
            if (runtimeReady && webView != null) {
                webView
            } else {
                pendingCommands.addLast(command)
                traceLocked("queue command pending=${pendingCommands.size} runtimeReady=$runtimeReady bound=${webView != null}")
                null
            }
        }

        if (target != null) {
            evaluateCommand(target, command)
        }
    }

    private fun flushPendingCommands() {
        val targetAndCommands = synchronized(this) {
            val target = webView
            if (!runtimeReady || target == null || pendingCommands.isEmpty()) {
                null
            } else {
                val commands = mutableListOf<String>()
                while (pendingCommands.isNotEmpty()) {
                    commands += pendingCommands.removeFirst()
                }
                target to commands
            }
        } ?: return

        val (target, commands) = targetAndCommands
        commands.forEach { command ->
            evaluateCommand(target, command)
        }
    }

    private fun evaluateCommand(
        target: WebView,
        command: String
    ) {
        val script = "window.KmdRuntime && window.KmdRuntime.receive(${RuntimeMessageCodec.quoteForJavascript(command)});"
        val action = {
            if (synchronized(this) { webView === target }) {
                if (target.isAttachedToWindow) {
                    runCatching {
                        trace("eval command webView=${System.identityHashCode(target)} chars=${command.length}")
                        target.evaluateJavascript(script, null)
                    }.onFailure { error ->
                        Log.w(LogTag, "Skipped runtime command after WebView became unavailable.", error)
                    }
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun nextMessageIdLocked(): String {
        nextMessageNumber += 1
        return "android-$nextMessageNumber"
    }

    private fun traceRuntimeEventLocked(event: ReaderRuntimeEvent) {
        when (event) {
            is ReaderRuntimeEvent.TransportReady -> {
                traceLocked("event transportReady runtime=${event.runtime} version=${event.version} session=${event.sessionId ?: "-"}")
            }
            is ReaderRuntimeEvent.Ready -> {
                traceLocked("event ready work=${event.workId} duration=${event.durationMs ?: -1} session=${event.sessionId ?: "-"}")
            }
            is ReaderRuntimeEvent.ProgressChanged -> {
                val now = System.currentTimeMillis()
                if (now - lastProgressTraceAtMs >= 1000L) {
                    lastProgressTraceAtMs = now
                    traceLocked("event progress work=${event.workId} progress=${event.progress} time=${event.timeMs ?: -1}")
                }
            }
            is ReaderRuntimeEvent.PlaybackStateChanged -> {
                traceLocked("event playback work=${event.workId} playing=${event.isPlaying} state=${event.state ?: "-"}")
            }
            is ReaderRuntimeEvent.InspectionReported -> {
                traceLocked("event inspection work=${event.workId} issues=${event.issues.size}")
            }
            is ReaderRuntimeEvent.Failed -> {
                traceLocked("event failed work=${event.workId ?: "-"} message=${event.message}")
            }
        }
    }

    private fun trace(message: String) {
        synchronized(this) {
            traceLocked(message)
        }
    }

    private fun traceLocked(message: String) {
        traceEntries.addLast("${System.currentTimeMillis()} ${message.truncateForTrace()}")
        while (traceEntries.size > 40) {
            traceEntries.removeFirst()
        }
    }

    private fun String.truncateForTrace(maxLength: Int = 220): String =
        if (length <= maxLength) {
            this
        } else {
            take(maxLength) + "...(truncated)"
        }
}
