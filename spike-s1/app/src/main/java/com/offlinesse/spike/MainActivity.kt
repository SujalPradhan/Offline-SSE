package com.offlinesse.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var ssidText: TextView
    private lateinit var passwordText: TextView
    private lateinit var ipText: TextView
    private lateinit var clientsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var broadcastButton: Button

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var httpServer: SpikeHttpServer? = null
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ssidText = findViewById(R.id.ssidText)
        passwordText = findViewById(R.id.passwordText)
        ipText = findViewById(R.id.ipText)
        clientsText = findViewById(R.id.clientsText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        broadcastButton = findViewById(R.id.broadcastButton)

        startButton.setOnClickListener { requestPermissionsAndStart() }
        stopButton.setOnClickListener { stopHotspot() }
        broadcastButton.setOnClickListener { broadcastTestEvent() }

        updateUI("Idle", "", "", "", 0)
    }

    // ── Permissions ──────────────────────────────────────────────────────────

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
            startHotspot()
        } else {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startHotspot()
        } else {
            updateUI("Permission denied — cannot start hotspot", "", "", "", 0)
        }
    }

    // ── Hotspot ───────────────────────────────────────────────────────────────

    private fun startHotspot() {
        updateUI("Starting hotspot...", "", "", "", 0)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                hotspotReservation = reservation
                val config = reservation.wifiConfiguration
                val ssid = config?.SSID?.replace("\"", "") ?: "Unknown"
                val password = config?.preSharedKey?.replace("\"", "") ?: "Unknown"

                // Start HTTP server after hotspot is up
                val server = SpikeHttpServer(8080) { count ->
                    mainHandler.post { clientsText.text = "Connected receivers: $count" }
                }
                httpServer = server
                executor.execute { server.start() }

                // Give server a moment to bind, then get IP
                mainHandler.postDelayed({
                    val ip = getHotspotIp()
                    updateUI("✅ Hotspot active", ssid, password, ip, 0)
                }, 500)
            }

            override fun onStopped() {
                mainHandler.post { updateUI("Hotspot stopped", "", "", "", 0) }
            }

            override fun onFailed(reason: Int) {
                mainHandler.post {
                    updateUI("❌ Hotspot failed (reason: $reason)", "", "", "", 0)
                }
            }
        }, mainHandler)
    }

    private fun stopHotspot() {
        httpServer?.stop()
        httpServer = null
        hotspotReservation?.close()
        hotspotReservation = null
        updateUI("Stopped", "", "", "", 0)
    }

    private fun broadcastTestEvent() {
        httpServer?.broadcastMessage("Hello from Sender at ${System.currentTimeMillis()}")
    }

    // ── Network helpers ───────────────────────────────────────────────────────

    private fun getHotspotIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.name.contains("wlan") || it.name.contains("ap") }
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                .map { it.hostAddress ?: "" }
                .firstOrNull() ?: "IP not found"
        } catch (e: Exception) {
            "Error getting IP: ${e.message}"
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun updateUI(status: String, ssid: String, password: String, ip: String, clients: Int) {
        statusText.text = "Status: $status"
        ssidText.text = if (ssid.isNotEmpty()) "SSID: $ssid" else "SSID: —"
        passwordText.text = if (password.isNotEmpty()) "Password: $password" else "Password: —"
        ipText.text = if (ip.isNotEmpty()) "URL: http://$ip:8080" else "URL: —"
        clientsText.text = "Connected receivers: $clients"
        stopButton.isEnabled = hotspotReservation != null
        broadcastButton.isEnabled = hotspotReservation != null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHotspot()
        executor.shutdownNow()
    }
}

// ── Minimal HTTP + SSE Server ─────────────────────────────────────────────────

class SpikeHttpServer(
    private val port: Int,
    private val onClientCountChange: (Int) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val sseClients = CopyOnWriteArrayList<OutputStream>()
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false

    fun start() {
        running = true
        serverSocket = ServerSocket(port)
        while (running) {
            try {
                val client = serverSocket!!.accept()
                executor.execute { handleClient(client) }
            } catch (e: Exception) {
                if (running) e.printStackTrace()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"

            when {
                path == "/events" -> handleSse(socket)
                path == "/" -> handleRoot(socket)
                else -> send404(socket)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleRoot(socket: Socket) {
        val body = """
            <!DOCTYPE html>
            <html>
            <head><title>SSE Spike S1</title></head>
            <body style="font-family:sans-serif;padding:20px;background:#111;color:#fff">
              <h1>SSE Spike S1 — Receiver</h1>
              <p id="status">Connecting to event stream...</p>
              <div id="messages"></div>
              <script>
                const es = new EventSource('/events');
                es.onopen = () => document.getElementById('status').textContent = '✅ Connected to SSE stream';
                es.onmessage = (e) => {
                  const div = document.createElement('div');
                  div.style = 'background:#1e1e1e;padding:12px;margin:8px 0;border-radius:6px;font-size:18px';
                  div.textContent = e.data;
                  document.getElementById('messages').prepend(div);
                };
                es.onerror = () => document.getElementById('status').textContent = '❌ SSE connection lost';
              </script>
            </body>
            </html>
        """.trimIndent()

        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        socket.getOutputStream().write(response.toByteArray())
        socket.close()
    }

    private fun handleSse(socket: Socket) {
        val out = socket.getOutputStream()
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        out.write(headers.toByteArray())
        out.flush()

        sseClients.add(out)
        onClientCountChange(sseClients.size)

        // Send a welcome ping
        try {
            out.write(": ping\n\n".toByteArray())
            out.flush()
        } catch (_: Exception) {}

        // Keep connection alive — will be closed when client disconnects or server stops
        try {
            while (running && !socket.isClosed) {
                Thread.sleep(5000)
                out.write(": keepalive\n\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            sseClients.remove(out)
            onClientCountChange(sseClients.size)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun broadcastMessage(text: String) {
        val event = "data: $text\n\n"
        val dead = mutableListOf<OutputStream>()
        for (client in sseClients) {
            try {
                client.write(event.toByteArray())
                client.flush()
            } catch (_: Exception) {
                dead.add(client)
            }
        }
        sseClients.removeAll(dead)
        onClientCountChange(sseClients.size)
    }

    private fun send404(socket: Socket) {
        socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
        socket.close()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
