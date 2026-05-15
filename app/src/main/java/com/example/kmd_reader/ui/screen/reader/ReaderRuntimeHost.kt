package com.example.kmd_reader.ui.screen.reader

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.webview.RuntimeJavascriptBridge
import com.example.kmd_reader.runtime.webview.WebViewReaderRuntimeBridge

private const val RuntimeAssetUrl = "file:///android_asset/kmd-runtime/index.html"
private const val RuntimeBridgeName = "KmdAndroid"

@Composable
fun ReaderRuntimeHost(
    runtimeBridge: ReaderRuntimeBridge?,
    modifier: Modifier = Modifier
) {
    val webViewBridge = runtimeBridge as? WebViewReaderRuntimeBridge

    if (webViewBridge == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
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

    AndroidView(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.scrim),
        factory = { context ->
            WebView(context).apply {
                configureForRuntime(webViewBridge)
                webViewBridge.bind(this)
                loadUrl(RuntimeAssetUrl)
            }
        },
        update = { webView ->
            if (webView.url == null) {
                webView.loadUrl(RuntimeAssetUrl)
            }
        },
        onRelease = { webView ->
            webViewBridge.unbind(webView)
            webView.removeJavascriptInterface(RuntimeBridgeName)
            webView.stopLoading()
            webView.destroy()
        }
    )
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForRuntime(runtimeBridge: WebViewReaderRuntimeBridge) {
    setBackgroundColor(Color.TRANSPARENT)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.mediaPlaybackRequiresUserGesture = false
    settings.allowContentAccess = false
    settings.allowFileAccess = true
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
    addJavascriptInterface(RuntimeJavascriptBridge(runtimeBridge), RuntimeBridgeName)

    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            return false
        }
    }
    webViewClient = object : WebViewClient() {
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                runtimeBridge.reportHostError(
                    error.description?.toString() ?: "WebView Runtime 加载失败"
                )
            }
        }
    }
}
