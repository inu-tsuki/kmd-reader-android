package com.example.kmd_reader.ui.screen.reader

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kmd_reader.BuildConfig
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.webview.RuntimeJavascriptBridge
import com.example.kmd_reader.runtime.webview.WebViewReaderRuntimeBridge
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color as ComposeColor

private const val RuntimeAssetHost = "kmd-reader-runtime.local"
private const val ReaderRuntimeAssetPath = "reader-runtime/index.html"
private const val D0RuntimeAssetPath = "kmd-runtime/index.html"
private const val RuntimeBridgeName = "KmdAndroid"
private const val RuntimeHostLogTag = "KmdReaderWebView"

@Composable
fun ReaderRuntimeHost(
    runtimeBridge: ReaderRuntimeBridge?,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    onTwoFingerTap: () -> Unit = {},
    onRuntimeDoubleTap: () -> Unit = {}
) {
    val webViewBridge = runtimeBridge as? WebViewReaderRuntimeBridge
    var rendererGone by remember(webViewBridge) { mutableStateOf(false) }
    val latestTwoFingerTap = rememberUpdatedState(onTwoFingerTap)
    val latestRuntimeDoubleTap = rememberUpdatedState(onRuntimeDoubleTap)
    val context = LocalContext.current
    val visualDebugEnabled = remember(context) {
        context.isRuntimeVisualDebugEnabled()
    }
    val containerModifier = if (framed) {
        modifier.clip(RoundedCornerShape(8.dp))
    } else {
        modifier
    }

    if (webViewBridge == null) {
        Box(
            modifier = containerModifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "当前 Runtime Bridge 不需要 WebView 宿主。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (rendererGone) {
        Box(
            modifier = containerModifier
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Reader Runtime 渲染进程已退出。请返回详情页后重新进入阅读，或切换模拟器图形设置后重试。",
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        return
    }

    var hostSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = containerModifier
            .background(if (visualDebugEnabled) ComposeColor(0xFF25002E) else ComposeColor.Black)
            .onGloballyPositioned { coordinates ->
                val nextSize = coordinates.size
                if (hostSize != nextSize) {
                    hostSize = nextSize
                    if (visualDebugEnabled) {
                        Log.i(RuntimeHostLogTag, "Compose host laid out: ${nextSize.width}x${nextSize.height}")
                    }
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val runtimeUrl = context.runtimeAssetUrl(visualDebugEnabled)
                val hostState = RuntimeWebViewHostState()
                WebView(context).apply {
                    configureForRuntime(
                        runtimeBridge = webViewBridge,
                        visualDebugEnabled = visualDebugEnabled,
                        hostState = hostState,
                        onTwoFingerTap = { latestTwoFingerTap.value() },
                        onRuntimeDoubleTap = { latestRuntimeDoubleTap.value() },
                        onRenderProcessGone = {
                            rendererGone = true
                        }
                    )
                    setTag(hostState)
                    webViewBridge.bind(this)
                    Log.i(RuntimeHostLogTag, "Loading runtime url=$runtimeUrl")
                    loadUrl(runtimeUrl)
                }
            },
            update = { webView ->
                if (webView.url == null) {
                    val runtimeUrl = webView.context.runtimeAssetUrl(visualDebugEnabled)
                    Log.i(RuntimeHostLogTag, "Reloading runtime because WebView url is null: $runtimeUrl")
                    webView.loadUrl(runtimeUrl)
                }
            },
        onRelease = { webView ->
                val hostState = webView.tag as? RuntimeWebViewHostState
                hostState?.breadcrumbs?.add("release rendererGone=${hostState.rendererGone} url=${webView.url ?: "-"}")
                hostState?.debugProbeScheduler?.cancel(webView)
                webViewBridge.unbind(webView)
                runCatching {
                    if (hostState?.rendererGone != true) {
                        webView.removeJavascriptInterface(RuntimeBridgeName)
                        webView.stopLoading()
                    }
                    webView.destroy()
                }.onFailure { error ->
                    Log.w(RuntimeHostLogTag, "Failed to release WebView cleanly.", error)
                }
            }
        )

        if (visualDebugEnabled) {
            Text(
                text = "Android WebView ${hostSize.width}x${hostSize.height}",
                color = ComposeColor.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(ComposeColor(0xCC7A1FA2), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForRuntime(
    runtimeBridge: WebViewReaderRuntimeBridge,
    visualDebugEnabled: Boolean,
    hostState: RuntimeWebViewHostState,
    onTwoFingerTap: () -> Unit,
    onRuntimeDoubleTap: () -> Unit,
    onRenderProcessGone: () -> Unit
) {
    setBackgroundColor(if (visualDebugEnabled) AndroidColor.rgb(37, 0, 46) else AndroidColor.BLACK)
    settings.javaScriptEnabled = true
    settings.cacheMode = if (visualDebugEnabled) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    settings.domStorageEnabled = false
    settings.mediaPlaybackRequiresUserGesture = false
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.allowContentAccess = false
    settings.allowFileAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }

    val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    WebView.setWebContentsDebuggingEnabled(isDebuggable)
    if (visualDebugEnabled) {
        clearCache(true)
    }
    addJavascriptInterface(RuntimeJavascriptBridge(runtimeBridge), RuntimeBridgeName)
    val hostGestureObserver = RuntimeHostGestureObserver(
        viewConfiguration = ViewConfiguration.get(context),
        onTwoFingerTap = onTwoFingerTap,
        onSingleFingerDoubleTap = onRuntimeDoubleTap
    )
    setOnTouchListener { _, event ->
        hostGestureObserver.onTouchEvent(event)
        false
    }
    if (visualDebugEnabled) {
        addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            Log.i(
                RuntimeHostLogTag,
                "WebView laid out: ${right - left}x${bottom - top}, view=${view.width}x${view.height}, hardware=${view.isHardwareAccelerated}"
            )
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            hostState.breadcrumbs.add(
                "console ${consoleMessage.messageLevel()} " +
                    "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} " +
                    consoleMessage.message()
            )
            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                runtimeBridge.reportHostError(
                    buildString {
                        append("WebView console error: ")
                        append(consoleMessage.message())
                        append(" (")
                        append(consoleMessage.sourceId())
                        append(":")
                        append(consoleMessage.lineNumber())
                        append(")")
                    }
                )
                return true
            }
            return false
        }
    }
    webViewClient = object : WebViewClient() {
        override fun onPageCommitVisible(view: WebView, url: String?) {
            hostState.breadcrumbs.add(
                "pageCommit url=${url ?: "-"} size=${view.width}x${view.height} attached=${view.isAttachedToWindow}"
            )
            if (visualDebugEnabled) {
                Log.i(
                    RuntimeHostLogTag,
                    "Runtime page committed visible: url=$url, size=${view.width}x${view.height}, hardware=${view.isHardwareAccelerated}"
                )
                view.injectDomVisibilityProbe("commit")
                hostState.debugProbeScheduler.post(view, 250L) { injectDomVisibilityProbe("commit+250ms") }
                hostState.debugProbeScheduler.post(view, 1000L) { inspectRuntimeDom("commit+1000ms") }
                hostState.debugProbeScheduler.post(view, 3000L) { inspectRuntimeDom("commit+3000ms") }
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            hostState.breadcrumbs.add(
                "pageFinished url=${url ?: "-"} size=${view.width}x${view.height} attached=${view.isAttachedToWindow}"
            )
            if (visualDebugEnabled) {
                Log.i(
                    RuntimeHostLogTag,
                    "Runtime page finished: url=$url, size=${view.width}x${view.height}"
                )
                view.injectDomVisibilityProbe("finished")
                view.inspectRuntimeDom("finished")
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url
            if (url.host != RuntimeAssetHost) {
                return null
            }

            val assetPath = url.path
                ?.removePrefix("/")
                ?.takeIf { it.isNotBlank() }
                ?: return null

            hostState.breadcrumbs.add("assetRequest path=$assetPath")
            val response = view.context.openRuntimeAsset(assetPath)
            if (response == null) {
                hostState.breadcrumbs.add("assetMiss path=$assetPath")
            }
            return response
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            hostState.breadcrumbs.add(
                "receivedError main=${request.isForMainFrame} url=${request.url} " +
                    "description=${error.description ?: "-"}"
            )
            if (request.isForMainFrame) {
                runtimeBridge.reportHostError(
                    error.description?.toString() ?: "WebView Runtime 加载失败"
                )
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            val rendererMessage = buildString {
                append("WebView renderer ")
                append(if (detail.didCrash()) "crashed" else "exited")
                append("; priority=")
                append(detail.rendererPriorityAtExit())
            }
            hostState.breadcrumbs.add(
                "renderProcessGone didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()} " +
                    "url=${view.url ?: "-"} size=${view.width}x${view.height}"
            )
            logRendererCrashReport(
                buildString {
                    appendLine(rendererMessage)
                    appendLine("viewUrl=${view.url ?: "-"}")
                    appendLine("viewSize=${view.width}x${view.height}")
                    appendLine("attached=${view.isAttachedToWindow}")
                    appendLine("hardwareAccelerated=${view.isHardwareAccelerated}")
                    appendLine("memory=${view.context.runtimeMemorySnapshot()}")
                    appendLine()
                    appendLine("Bridge snapshot:")
                    append(runtimeBridge.debugSnapshot())
                    appendLine()
                    appendLine("Host breadcrumbs:")
                    append(hostState.breadcrumbs.snapshot())
                }
            )
            runtimeBridge.reportHostError(rendererMessage)
            hostState.rendererGone = true
            hostState.debugProbeScheduler.cancel(view)
            runtimeBridge.unbind(view)
            onRenderProcessGone()
            return true
        }
    }
}

private class RuntimeWebViewHostState {
    val debugProbeScheduler = RuntimeDebugProbeScheduler()
    val breadcrumbs = RuntimeCrashBreadcrumbs()
    var rendererGone = false
}

private class RuntimeHostGestureObserver(
    viewConfiguration: ViewConfiguration,
    private val onTwoFingerTap: () -> Unit,
    private val onSingleFingerDoubleTap: () -> Unit
) {
    private val touchSlopPx = viewConfiguration.scaledTouchSlop * 1.5f
    private val doubleTapSlopPx = viewConfiguration.scaledDoubleTapSlop.toFloat()
    private val maxTapDurationMs = ViewConfiguration.getTapTimeout().toLong()
    private val maxTwoFingerTapDurationMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val maxDoubleTapDelayMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var trackingTwoFingerTap = false
    private var trackingSingleTap = false
    private var twoFingerStartTimeMs = 0L
    private var singleTapStartTimeMs = 0L
    private var firstPointerId = -1
    private var secondPointerId = -1
    private var firstStartX = 0f
    private var firstStartY = 0f
    private var secondStartX = 0f
    private var secondStartY = 0f
    private var singlePointerId = -1
    private var singleStartX = 0f
    private var singleStartY = 0f
    private var lastTapUpTimeMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startSingleTapTracking(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelSingleTapTracking()
                if (event.pointerCount == 2) {
                    startTwoFingerTracking(event)
                } else {
                    resetTwoFingerTracking()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackingSingleTap && singleTapMovedTooFar(event)) {
                    cancelSingleTapTracking()
                }
                if (trackingTwoFingerTap && (event.pointerCount != 2 || twoFingerMovedTooFar(event))) {
                    resetTwoFingerTracking()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (
                    trackingTwoFingerTap &&
                    event.pointerCount == 2 &&
                    event.eventTime - twoFingerStartTimeMs <= maxTwoFingerTapDurationMs &&
                    !twoFingerMovedTooFar(event)
                ) {
                    onTwoFingerTap()
                }
                resetTwoFingerTracking()
            }
            MotionEvent.ACTION_UP -> {
                if (
                    trackingSingleTap &&
                    event.eventTime - singleTapStartTimeMs <= maxTapDurationMs &&
                    !singleTapMovedTooFar(event)
                ) {
                    handleSingleTapUp(event)
                }
                cancelSingleTapTracking()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelSingleTapTracking()
                resetTwoFingerTracking()
            }
        }
    }

    private fun startSingleTapTracking(event: MotionEvent) {
        trackingSingleTap = true
        singleTapStartTimeMs = event.eventTime
        singlePointerId = event.getPointerId(0)
        singleStartX = event.getX(0)
        singleStartY = event.getY(0)
        resetTwoFingerTracking()
    }

    private fun startTwoFingerTracking(event: MotionEvent) {
        trackingTwoFingerTap = true
        twoFingerStartTimeMs = event.eventTime
        firstPointerId = event.getPointerId(0)
        secondPointerId = event.getPointerId(1)
        firstStartX = event.getX(0)
        firstStartY = event.getY(0)
        secondStartX = event.getX(1)
        secondStartY = event.getY(1)
    }

    private fun handleSingleTapUp(event: MotionEvent) {
        val tapX = event.x
        val tapY = event.y
        val isDoubleTap = lastTapUpTimeMs > 0L &&
            event.eventTime - lastTapUpTimeMs <= maxDoubleTapDelayMs &&
            distanceSquared(tapX - lastTapX, tapY - lastTapY) <= doubleTapSlopPx * doubleTapSlopPx

        if (isDoubleTap) {
            lastTapUpTimeMs = 0L
            onSingleFingerDoubleTap()
        } else {
            lastTapUpTimeMs = event.eventTime
            lastTapX = tapX
            lastTapY = tapY
        }
    }

    private fun singleTapMovedTooFar(event: MotionEvent): Boolean {
        val index = event.findPointerIndex(singlePointerId)
        if (index < 0) {
            return true
        }
        return distanceSquared(
            event.getX(index) - singleStartX,
            event.getY(index) - singleStartY
        ) > touchSlopPx * touchSlopPx
    }

    private fun twoFingerMovedTooFar(event: MotionEvent): Boolean {
        val firstIndex = event.findPointerIndex(firstPointerId)
        val secondIndex = event.findPointerIndex(secondPointerId)
        if (firstIndex < 0 || secondIndex < 0) {
            return true
        }

        return distanceSquared(
            event.getX(firstIndex) - firstStartX,
            event.getY(firstIndex) - firstStartY
        ) > touchSlopPx * touchSlopPx ||
            distanceSquared(
                event.getX(secondIndex) - secondStartX,
                event.getY(secondIndex) - secondStartY
            ) > touchSlopPx * touchSlopPx
    }

    private fun distanceSquared(dx: Float, dy: Float): Float = dx * dx + dy * dy

    private fun cancelSingleTapTracking() {
        trackingSingleTap = false
        singleTapStartTimeMs = 0L
        singlePointerId = -1
    }

    private fun resetTwoFingerTracking() {
        trackingTwoFingerTap = false
        twoFingerStartTimeMs = 0L
        firstPointerId = -1
        secondPointerId = -1
    }
}

private class RuntimeCrashBreadcrumbs {
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun add(message: String) {
        entries.addLast("${System.currentTimeMillis()} ${message.truncateForLog()}")
        while (entries.size > 40) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): String =
        if (entries.isEmpty()) {
            "  <empty>\n"
        } else {
            entries.joinToString(separator = "\n", postfix = "\n") { entry ->
                "  $entry"
            }
        }
}

private class RuntimeDebugProbeScheduler {
    private val callbacks = mutableListOf<Runnable>()

    fun post(webView: WebView, delayMs: Long, block: WebView.() -> Unit) {
        lateinit var callback: Runnable
        callback = Runnable {
            callbacks.remove(callback)
            if (webView.isAttachedToWindow) {
                webView.block()
            }
        }
        callbacks += callback
        webView.postDelayed(callback, delayMs)
    }

    fun cancel(webView: WebView) {
        callbacks.forEach(webView::removeCallbacks)
        callbacks.clear()
    }
}

private fun WebView.injectDomVisibilityProbe(reason: String) {
    val safeReason = reason
        .replace("\\", "\\\\")
        .replace("'", "\\'")
    evaluateJavascriptSafely(
        """
        (function () {
          try {
            document.documentElement.style.background = '#240033';
            document.body.style.background = '#240033';
            document.body.style.margin = '0';
            var probe = document.getElementById('kmd-android-webview-canary');
            if (!probe) {
              probe = document.createElement('div');
              probe.id = 'kmd-android-webview-canary';
              document.body.appendChild(probe);
            }
            probe.textContent = 'ANDROID JS PROBE ${safeReason} ' + window.innerWidth + 'x' + window.innerHeight;
            probe.style.cssText = [
              'position:fixed',
              'left:12px',
              'right:12px',
              'top:96px',
              'z-index:2147483647',
              'min-height:52px',
              'padding:10px 12px',
              'border:4px solid #ff3366',
              'background:#ffea00',
              'color:#111111',
              'font:700 16px/1.35 sans-serif',
              'letter-spacing:0',
              'pointer-events:none'
            ].join(';');
            return JSON.stringify({
              ok: true,
              reason: '${safeReason}',
              readyState: document.readyState,
              bodyChildren: document.body ? document.body.children.length : -1,
              width: window.innerWidth,
              height: window.innerHeight
            });
          } catch (error) {
            return JSON.stringify({ ok: false, reason: '${safeReason}', message: String(error) });
          }
        })();
        """.trimIndent()
    ) { result ->
        Log.i(RuntimeHostLogTag, "DOM visibility probe injected: $result")
        if (isAttachedToWindow) {
            inspectRuntimeDom("after-inject-$safeReason")
        }
    }
}

private fun WebView.inspectRuntimeDom(reason: String) {
    val safeReason = reason
        .replace("\\", "\\\\")
        .replace("'", "\\'")
    evaluateJavascriptSafely(
        """
        (function () {
          try {
            var root = document.getElementById('reader-root');
            var status = document.getElementById('runtime-status');
            var canvases = Array.prototype.slice.call(document.querySelectorAll('canvas'));
            var kmdDomProbes = Array.prototype.slice.call(document.querySelectorAll('[data-kmd-runtime-probe="dom"]'));
            var canary = document.getElementById('kmd-android-webview-canary');
            function rectOf(node) {
              if (!node || !node.getBoundingClientRect) return null;
              var rect = node.getBoundingClientRect();
              return {
                x: Math.round(rect.x),
                y: Math.round(rect.y),
                width: Math.round(rect.width),
                height: Math.round(rect.height)
              };
            }
            function styleOf(node) {
              if (!node) return null;
              var style = window.getComputedStyle(node);
              return {
                display: style.display,
                visibility: style.visibility,
                opacity: style.opacity,
                position: style.position,
                zIndex: style.zIndex,
                background: style.backgroundColor,
                color: style.color
              };
            }
            return JSON.stringify({
              reason: '${safeReason}',
              readyState: document.readyState,
              href: location.href,
              viewport: { width: window.innerWidth, height: window.innerHeight, dpr: window.devicePixelRatio },
              runtimeApi: {
                hasKmdRuntime: !!window.KmdRuntime,
                hasReceive: typeof (window.KmdRuntime && window.KmdRuntime.receive) === 'function',
                sessionId: window.KmdRuntime && window.KmdRuntime.getSessionId ? window.KmdRuntime.getSessionId() : null
              },
              body: {
                children: document.body ? document.body.children.length : -1,
                rect: rectOf(document.body),
                style: styleOf(document.body)
              },
              root: {
                exists: !!root,
                rect: rectOf(root),
                style: styleOf(root),
                childCount: root ? root.children.length : -1,
                childTags: root ? Array.prototype.slice.call(root.children).map(function (child) {
                  return child.tagName + (child.id ? '#' + child.id : '') + (child.getAttribute('data-kmd-runtime-probe') ? '[probe=' + child.getAttribute('data-kmd-runtime-probe') + ']' : '');
                }) : []
              },
              status: {
                exists: !!status,
                text: status ? status.textContent : null,
                rect: rectOf(status),
                style: styleOf(status)
              },
              canary: {
                exists: !!canary,
                rect: rectOf(canary),
                style: styleOf(canary),
                text: canary ? canary.textContent : null
              },
              probes: {
                kmdDomCount: kmdDomProbes.length,
                kmdDom: kmdDomProbes.map(function (node) {
                  return { text: node.textContent, rect: rectOf(node), style: styleOf(node) };
                })
              },
              canvases: canvases.map(function (canvas) {
                return {
                  width: canvas.width,
                  height: canvas.height,
                  rect: rectOf(canvas),
                  style: styleOf(canvas),
                  outline: canvas.style.outline,
                  cssWidth: canvas.style.width,
                  cssHeight: canvas.style.height
                };
              })
            });
          } catch (error) {
            return JSON.stringify({ reason: '${safeReason}', error: String(error) });
          }
        })();
        """.trimIndent()
    ) { result ->
        Log.i(RuntimeHostLogTag, "Runtime DOM snapshot: $result")
    }
}

private fun WebView.evaluateJavascriptSafely(
    script: String,
    callback: ((String) -> Unit)? = null
) {
    if (!isAttachedToWindow) {
        Log.i(RuntimeHostLogTag, "Skipped JS evaluation because WebView is detached.")
        return
    }
    runCatching {
        evaluateJavascript(script, callback)
    }.onFailure { error ->
        Log.w(RuntimeHostLogTag, "Skipped JS evaluation after WebView became unavailable.", error)
    }
}

private fun Context.runtimeAssetUrl(visualDebugEnabled: Boolean): String {
    val assetPath = if (hasRuntimeAsset(ReaderRuntimeAssetPath)) {
        ReaderRuntimeAssetPath
    } else {
        D0RuntimeAssetPath
    }
    val runtimeVersion = runCatching {
        packageManager.getPackageInfo(packageName, 0).lastUpdateTime
    }.getOrDefault(System.currentTimeMillis())
    val debugQuery = if (visualDebugEnabled) "&kmdDebugProbe=1" else ""
    return "https://$RuntimeAssetHost/$assetPath?v=$runtimeVersion$debugQuery"
}

private fun Context.isRuntimeVisualDebugEnabled(): Boolean =
    BuildConfig.DEBUG &&
        BuildConfig.RUNTIME_VISUAL_DEBUG_PROBES &&
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

private fun Context.hasRuntimeAsset(path: String): Boolean =
    runCatching {
        assets.open(path).use { Unit }
    }.isSuccess

private fun Context.openRuntimeAsset(path: String): WebResourceResponse? {
    val stream = runCatching {
        assets.open(path)
    }.getOrNull() ?: return null
    val mimeType = mimeTypeForAsset(path)
    val encoding = if (mimeType.startsWith("text/") || mimeType == "application/json") {
        "UTF-8"
    } else {
        null
    }
    return WebResourceResponse(mimeType, encoding, stream)
}

private fun Context.runtimeMemorySnapshot(): String {
    val runtime = Runtime.getRuntime()
    val usedBytes = runtime.totalMemory() - runtime.freeMemory()
    val totalBytes = runtime.totalMemory()
    val maxBytes = runtime.maxMemory()
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memoryInfo)
    return buildString {
        append("javaUsed=${usedBytes.toMiB()}MiB")
        append(", javaTotal=${totalBytes.toMiB()}MiB")
        append(", javaMax=${maxBytes.toMiB()}MiB")
        append(", avail=${memoryInfo.availMem.toMiB()}MiB")
        append(", threshold=${memoryInfo.threshold.toMiB()}MiB")
        append(", lowMemory=${memoryInfo.lowMemory}")
    }
}

private fun logRendererCrashReport(report: String) {
    report.chunked(3500).forEachIndexed { index, chunk ->
        Log.e(RuntimeHostLogTag, "Renderer crash report part ${index + 1}:\n$chunk")
    }
}

private fun Long.toMiB(): Long = this / 1024L / 1024L

private fun String.truncateForLog(maxLength: Int = 240): String =
    if (length <= maxLength) {
        this
    } else {
        take(maxLength) + "...(truncated)"
    }

private fun mimeTypeForAsset(path: String): String =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "html" -> "text/html"
        "js" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "wasm" -> "application/wasm"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
