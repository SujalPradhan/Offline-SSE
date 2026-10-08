package com.offlinesse.broadcast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class BroadcastServer(
    private val context: Context,
    private val port: Int = 8080,
    private val onClientCountChange: (Int) -> Unit,
    private val onNewEvent: (String) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private val sseClients = CopyOnWriteArrayList<OutputStream>()
    private val executor = Executors.newCachedThreadPool()
    private val history = CopyOnWriteArrayList<JSONObject>()
    @Volatile private var running = false
    
    private var currentPoll: JSONObject? = null

    fun start() {
        running = true
        serverSocket = ServerSocket(port)
        executor.execute {
            while (running) {
                try {
                    val client = serverSocket!!.accept()
                    executor.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (running) e.printStackTrace()
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader()
            var line = input.readLine()
            val requestLine = line ?: return

            var contentLength = 0
            while (line != null && line.isNotBlank()) {
                line = input.readLine()
                if (line?.startsWith("Content-Length:", ignoreCase = true) == true) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            when {
                method == "GET" && path == "/events" -> handleSse(socket)
                method == "GET" && path == "/" -> handleRoot(socket)
                method == "GET" && path == "/history" -> handleHistory(socket)
                method == "POST" && path == "/message" -> {
                    val body = CharArray(contentLength)
                    input.read(body, 0, contentLength)
                    handlePostMessage(socket, String(body))
                }
                method == "POST" && path == "/vote" -> {
                    val body = CharArray(contentLength)
                    input.read(body, 0, contentLength)
                    handleVote(socket, String(body))
                }
                else -> sendResponse(socket, 404, "Not Found", "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleRoot(socket: Socket) {
        try {
            val html = context.assets.open("receiver/index.html").bufferedReader().use { it.readText() }
            sendResponse(socket, 200, "OK", "text/html", html)
        } catch (e: Exception) {
            sendResponse(socket, 500, "Internal Error", "text/plain", "Could not load receiver HTML")
        }
    }

    private fun handleHistory(socket: Socket) {
        val jsonArray = JSONArray(history)
        sendResponse(socket, 200, "OK", "application/json", jsonArray.toString())
    }

    private fun handlePostMessage(socket: Socket, body: String) {
        try {
            val json = JSONObject(body)
            val text = json.optString("text", "")
            if (text.isNotBlank()) {
                broadcastMessage(text)
                sendResponse(socket, 200, "OK", "application/json", "{\"status\":\"ok\"}")
            } else {
                sendResponse(socket, 400, "Bad Request", "application/json", "{\"error\":\"empty text\"}")
            }
        } catch (e: Exception) {
            sendResponse(socket, 400, "Bad Request", "application/json", "{\"error\":\"invalid json\"}")
        }
    }

    private fun handleVote(socket: Socket, body: String) {
        try {
            val json = JSONObject(body)
            val pollId = json.optString("pollId", "")
            val optionIdx = json.optInt("optionIndex", -1)
            
            val poll = currentPoll
            if (poll != null && poll.optString("id") == pollId && optionIdx >= 0) {
                val votesArray = poll.getJSONArray("votes")
                if (optionIdx < votesArray.length()) {
                    val currentCount = votesArray.getInt(optionIdx)
                    votesArray.put(optionIdx, currentCount + 1)
                    
                    val updateObj = JSONObject().apply {
                        put("type", "poll_update")
                        put("id", pollId)
                        put("votes", votesArray)
                    }
                    broadcastRawJson(updateObj)
                    sendResponse(socket, 200, "OK", "application/json", "{\"status\":\"ok\"}")
                    return
                }
            }
            sendResponse(socket, 400, "Bad Request", "application/json", "{\"error\":\"invalid vote\"}")
        } catch (e: Exception) {
            sendResponse(socket, 400, "Bad Request", "application/json", "{\"error\":\"invalid json\"}")
        }
    }

    private fun sendResponse(socket: Socket, code: Int, status: String, contentType: String, body: String) {
        val bodyBytes = body.toByteArray()
        val response = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
        try {
            val out = socket.getOutputStream()
            out.write(response.toByteArray())
            out.write(bodyBytes)
            out.flush()
        } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }

    private fun handleSse(socket: Socket) {
        val out = socket.getOutputStream()
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
        try {
            out.write(headers.toByteArray())
            out.flush()
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            return
        }

        sseClients.add(out)
        onClientCountChange(sseClients.size)

        try {
            out.write(": ping\n\n".toByteArray())
            out.flush()
        } catch (_: Exception) {}

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
    
    private fun broadcastRawJson(jsonObj: JSONObject) {
        val eventDataStr = jsonObj.toString()
        onNewEvent(eventDataStr)
        executor.execute {
            val eventString = "data: ${eventDataStr}\n\n"
            val dead = mutableListOf<OutputStream>()
            for (client in sseClients) {
                try {
                    client.write(eventString.toByteArray())
                    client.flush()
                } catch (e: Exception) {
                    dead.add(client)
                }
            }
            if (dead.isNotEmpty()) {
                sseClients.removeAll(dead)
                onClientCountChange(sseClients.size)
            }
        }
    }

    fun broadcastMessage(text: String) {
        val msgId = "msg-${System.currentTimeMillis()}"
        val msgObj = JSONObject().apply {
            put("type", "message")
            put("id", msgId)
            put("text", text)
            put("ts", System.currentTimeMillis())
        }
        history.add(msgObj)
        broadcastRawJson(msgObj)
    }
    
    fun startPoll(question: String, options: List<String>) {
        val pollId = "poll-${System.currentTimeMillis()}"
        val optArray = JSONArray(options)
        val voteArray = JSONArray()
        for (i in options.indices) {
            voteArray.put(0)
        }
        
        val pollObj = JSONObject().apply {
            put("type", "poll")
            put("id", pollId)
            put("ts", System.currentTimeMillis())
            put("question", question)
            put("options", optArray)
            put("votes", voteArray)
        }
        currentPoll = pollObj
        history.add(pollObj)
        broadcastRawJson(pollObj)
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
