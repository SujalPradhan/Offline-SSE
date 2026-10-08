package com.offlinesse.broadcast

import android.webkit.JavascriptInterface

class WebAppBridge(
    private val onStartHotspot: () -> Unit,
    private val onStopHotspot: () -> Unit,
    private val onBroadcast: (String) -> Unit
) {
    @JavascriptInterface
    fun startHotspot() {
        onStartHotspot()
    }

    @JavascriptInterface
    fun stopHotspot() {
        onStopHotspot()
    }

    @JavascriptInterface
    fun broadcast(message: String) {
        onBroadcast(message)
    }
}
