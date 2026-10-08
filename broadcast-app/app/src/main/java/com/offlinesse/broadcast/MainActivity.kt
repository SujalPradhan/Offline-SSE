package com.offlinesse.broadcast

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var hotspotManager: HotspotManager
    private var broadcastServer: BroadcastServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hotspotManager = HotspotManager(this)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        val bridge = WebAppBridge(
            onStartHotspot = { mainHandler.post { requestPermissionsAndStart() } },
            onStopHotspot = { mainHandler.post { stopServices() } },
            onBroadcast = { msg -> mainHandler.post { broadcastServer?.broadcastMessage(msg) } },
            onStartPoll = { q, opts -> mainHandler.post { broadcastServer?.startPoll(q, opts) } }
        )
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.loadUrl("file:///android_asset/sender/index.html")
    }

    private fun requestPermissionsAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startServices()
        } else {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startServices()
            } else {
                updateWebUIError("Permission denied. Cannot start hotspot.")
            }
        }
    }

    private fun startServices() {
        updateWebUIStatus("Starting hotspot...")
        hotspotManager.start(object : HotspotManager.Listener {
            override fun onHotspotStarted(ssid: String, password: String, ips: List<String>) {
                val server = BroadcastServer(this@MainActivity, 8080) { count ->
                    mainHandler.post { updateWebUIClients(count) }
                }
                broadcastServer = server
                server.start()
                
                val ipsJson = ips.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
                val script = "if(window.onHotspotActive) window.onHotspotActive(\"$ssid\", \"$password\", $ipsJson);"
                webView.evaluateJavascript(script, null)
            }

            override fun onHotspotStopped() {
                updateWebUIStatus("Idle")
                stopServices()
            }

            override fun onHotspotFailed(reason: Int) {
                updateWebUIError("Hotspot failed with reason code: $reason")
                stopServices()
            }
        })
    }

    private fun stopServices() {
        broadcastServer?.stop()
        broadcastServer = null
        hotspotManager.stop()
        webView.evaluateJavascript("if(window.onHotspotStopped) window.onHotspotStopped();", null)
    }

    private fun updateWebUIStatus(status: String) {
        webView.evaluateJavascript("if(window.updateStatus) window.updateStatus(\"$status\");", null)
    }

    private fun updateWebUIError(error: String) {
        webView.evaluateJavascript("if(window.showError) window.showError(\"$error\");", null)
    }

    private fun updateWebUIClients(count: Int) {
        webView.evaluateJavascript("if(window.updateClientCount) window.updateClientCount($count);", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServices()
    }
}
