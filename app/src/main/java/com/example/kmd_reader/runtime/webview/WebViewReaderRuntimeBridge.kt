package com.example.kmd_reader.runtime.webview

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.example.kmd_reader.runtime.ReaderLoadRequest
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderRuntimeEvent
import com.example.kmd_reader.runtime.ReaderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class WebViewReaderRuntimeBridge : ReaderRuntimeBridge {
    private val _events = MutableSharedFlow<ReaderRuntimeEvent>(
        extraBufferCapacity = 32
    )
    override val events: Flow<ReaderRuntimeEvent> = _events.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCommands = ArrayDeque<String>()

    private var webView: WebView? = null
    private var runtimeReady = false
    private var currentWorkId: String? = null
    private var lastLoadRequest: ReaderLoadRequest? = null
    private var nextMessageNumber = 0

    override suspend fun attach() = Unit

    override suspend fun load(request: ReaderLoadRequest) {
        synchronized(this) {
            currentWorkId = request.work.id
            lastLoadRequest = request
        }
        sendCommand {
            RuntimeMessageCodec.encodeLoadScript(
                request = request,
                id = nextMessageIdLocked()
            )
        }
    }

    override suspend fun play() {
        sendCommand {
            RuntimeMessageCodec.encodePlay(id = nextMessageIdLocked())
        }
    }

    override suspend fun pause() {
        sendCommand {
            RuntimeMessageCodec.encodePause(id = nextMessageIdLocked())
        }
    }

    override suspend fun seek(progress: Float) {
        sendCommand {
            RuntimeMessageCodec.encodeSeek(
                progress = progress,
                id = nextMessageIdLocked()
            )
        }
    }

    override suspend fun setInspectionEnabled(enabled: Boolean) {
        sendCommand {
            RuntimeMessageCodec.encodeSetInspectionEnabled(
                enabled = enabled,
                id = nextMessageIdLocked()
            )
        }
    }

    override suspend fun updateSettings(settings: ReaderSettings) {
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
            if (pendingCommands.isEmpty()) {
                lastLoadRequest?.let { request ->
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
                this.webView = null
                runtimeReady = false
            }
        }
    }

    fun receiveFromRuntime(rawMessage: String) {
        when (val message = RuntimeMessageCodec.decodeInbound(rawMessage)) {
            RuntimeInboundMessage.HostReady -> {
                synchronized(this) {
                    runtimeReady = true
                }
                flushPendingCommands()
            }
            is RuntimeInboundMessage.Event -> {
                if (message.event is ReaderRuntimeEvent.Ready) {
                    synchronized(this) {
                        currentWorkId = message.event.workId
                    }
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
        _events.tryEmit(
            ReaderRuntimeEvent.Failed(
                workId = synchronized(this) { currentWorkId },
                message = message
            )
        )
    }

    override fun dispose() {
        val disposeCommand = synchronized(this) {
            RuntimeMessageCodec.encodeDispose(id = nextMessageIdLocked())
        }
        sendCommand { disposeCommand }
        synchronized(this) {
            pendingCommands.clear()
            currentWorkId = null
            lastLoadRequest = null
            runtimeReady = false
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
                target.evaluateJavascript(script, null)
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
}
