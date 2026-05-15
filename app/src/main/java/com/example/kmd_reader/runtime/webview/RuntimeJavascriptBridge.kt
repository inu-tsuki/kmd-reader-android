package com.example.kmd_reader.runtime.webview

import android.webkit.JavascriptInterface

class RuntimeJavascriptBridge(
    private val runtimeBridge: WebViewReaderRuntimeBridge
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        runtimeBridge.receiveFromRuntime(message)
    }
}
