package com.offlinesse.broadcast

import android.webkit.JavascriptInterface
import org.json.JSONArray

class WebAppBridge(
    private val onStartHotspot: () -> Unit,
    private val onStopHotspot: () -> Unit,
    private val onBroadcast: (String) -> Unit,
    private val onStartPoll: (String, List<String>) -> Unit
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
    
    @JavascriptInterface
    fun startPoll(question: String, optionsJson: String) {
        try {
            val arr = JSONArray(optionsJson)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            onStartPoll(question, list)
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }
}
