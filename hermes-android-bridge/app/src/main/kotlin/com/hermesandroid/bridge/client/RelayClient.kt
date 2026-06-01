package com.hermesandroid.bridge.client

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.hermesandroid.bridge.util.AppLogger
import com.hermesandroid.bridge.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.server.CommandDispatcher
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.*
import okhttp3.*

/**
 * WebSocket client that connects OUT to the Hermes relay server.
 * Receives commands over WebSocket, dispatches them to [CommandDispatcher],
 * and sends results back.
 *
 * Auto-reconnects on disconnect with exponential backoff (1s, 2s, 4s, 8s, max 30s).
 */
object RelayClient {

    private const val TAG = "RelayClient"
    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY_SERVER_URL = "relay_server_url"
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MAX_RETRIES = 20  // high enough to survive dev relay restarts

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(5))  // WebSocket PING every 5s — keeps Tailscale DERP alive
        .build()

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var keepaliveJob: Job? = null
    private var prefs: SharedPreferences? = null
    private var appContext: android.content.Context? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var isConnecting: Boolean = false

    @Volatile
    private var shouldReconnect: Boolean = false

    var serverUrl: String?
        get() = prefs?.getString(KEY_SERVER_URL, null)
        set(value) { prefs?.edit()?.putString(KEY_SERVER_URL, value)?.apply() }

    /** Callback for UI updates. Called on main thread. */
    var onStatusChanged: ((connected: Boolean, message: String) -> Unit)? = null

    /** Callback for voice state changes. Called on main thread. */
    var onVoiceStateChanged: ((state: String) -> Unit)? = null

    /** Callback for chat responses from Hermes. Called on main thread. */
    var onChatResponse: ((text: String, cardsJson: String) -> Unit)? = null

    /** Callback for speech transcripts (partial and final). Called on main thread. */
    var onTranscript: ((text: String, isFinal: Boolean) -> Unit)? = null

    /** Callback for voice service status/errors. Called on main thread. */
    var onVoiceStatus: ((message: String, isError: Boolean) -> Unit)? = null

    /** Send a text chat message to Hermes via the relay. */
    fun sendChat(text: String) {
        val ws = webSocket ?: return
        if (!isConnected) return
        try {
            val message = org.json.JSONObject().apply {
                put("type", "chat")
                put("text", text)
                put("request_id", "chat_${System.currentTimeMillis()}")
            }
            ws.send(message.toString())
            AppLogger.i(TAG, "Sent chat message: ${text.take(50)}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to send chat message: ${e.message}")
        }
    }

    /** Send a command to the relay (e.g., /voice/start, /voice/stop). */
    fun sendCommand(method: String, path: String, body: org.json.JSONObject? = null) {
        val ws = webSocket ?: return
        if (!isConnected) return
        try {
            val requestId = "cmd_${System.currentTimeMillis()}"
            val message = org.json.JSONObject().apply {
                put("request_id", requestId)
                put("method", method)
                put("path", path)
                if (body != null) put("body", body)
            }
            ws.send(message.toString())
            AppLogger.i(TAG, "Sent command: $method $path (id=$requestId)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to send command: ${e.message}")
        }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        appContext = context.applicationContext
    }

    fun connect(serverUrl: String) {
        // Guard against concurrent connects
        if (isConnected || isConnecting) {
            AppLogger.w(TAG, "Connect called while already connected/connecting — ignoring")
            return
        }

        disconnect()

        this.serverUrl = serverUrl
        shouldReconnect = true
        isConnecting = true

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        doConnect(serverUrl)
    }

    fun disconnect() {
        shouldReconnect = false
        isConnecting = false
        keepaliveJob?.cancel()
        keepaliveJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        scope?.cancel()
        scope = null
        isConnected = false
        BridgeAccessibilityService.instance?.stopForeground()
        stopConnectionService()
        notifyStatus(false, "Disconnected")
    }

    /** Try to auto-connect if server URL was previously saved. */
    fun autoConnect() {
        val url = serverUrl
        if (!url.isNullOrBlank()) {
            AppLogger.i(TAG, "Auto-connecting to $url")
            connect(url)
        }
    }

    /**
     * Send a binary frame (raw PCM audio) to the relay server over the WebSocket.
     * Used by VoiceService to stream microphone audio.
     * Returns true if the frame was queued for sending, false if not connected.
     */
    fun sendBinary(data: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected) return false
        return try {
            ws.send(okio.ByteString.of(*data))
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to send binary frame: ${e.message}")
            false
        }
    }

    private fun doConnect(serverUrl: String) {
        val wsUrl = buildWsUrl(serverUrl)
        AppLogger.i(TAG, "Connecting to $wsUrl")
        notifyStatus(false, "Connecting to $wsUrl ...")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.i(TAG, "WebSocket connected to $serverUrl")
                isConnected = true
                isConnecting = false
                // Start client-side keepalive ping (10s interval)
                keepaliveJob?.cancel()
                keepaliveJob = scope?.launch {
                    while (isActive && isConnected) {
                        delay(5_000)
                        try {
                            if (isConnected && webSocket.send("{\"type\":\"ping\"}")) {
                                // sent ok
                            }
                        } catch (_: Exception) { }
                    }
                }
                try {
                    BridgeAccessibilityService.instance?.startForeground()
                } catch (e: SecurityException) {
                    AppLogger.w(TAG, "Could not promote bridge service to foreground", e)
                }
                // Start foreground service to keep connection alive in background
                startConnectionService()
                notifyStatus(true, "Connected to $serverUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope?.launch {
                    handleMessage(webSocket, text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Binary frame from relay — TTS audio to play
                scope?.launch {
                    handleBinaryMessage(bytes)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.i(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                isConnecting = false
                notifyStatus(false, "Closed: code=$code $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code ?: 0
                val errorDetail = "Error: ${t.javaClass.simpleName}: ${t.message} (HTTP $httpCode)"
                AppLogger.e(TAG, "WebSocket failure: $errorDetail", t)
                isConnected = false
                isConnecting = false
                notifyStatus(false, errorDetail)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val url = serverUrl ?: return

        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            var backoff = 1000L
            var retries = 0
            while (shouldReconnect && !isConnected && retries < MAX_RETRIES) {
                retries++
                AppLogger.i(TAG, "Reconnecting in ${backoff}ms... (attempt $retries/$MAX_RETRIES)")
                notifyStatus(false, "Reconnecting in ${backoff / 1000}s... (attempt $retries/$MAX_RETRIES)")
                delay(backoff)
                if (shouldReconnect && !isConnected) {
                    doConnect(url)
                    delay(3000)
                    if (!isConnected) {
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    } else {
                        break
                    }
                }
            }
            if (!isConnected && retries >= MAX_RETRIES) {
                notifyStatus(false, "Failed to connect after $MAX_RETRIES attempts. Tap Connect to retry.")
                shouldReconnect = false
            }
        }
    }

    private fun buildWsUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val useTls = trimmed.startsWith("https://") || trimmed.startsWith("wss://")
        var base = trimmed
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("ws://").removePrefix("wss://")
        if (!base.contains(":")) {
            base = "$base:8766"
        }
        val scheme = if (useTls) "wss" else "ws"
        val deviceId = try {
            // Use brand-model-serialhash for a URL-safe, readable device ID
            val serial = (Build.SERIAL ?: Build.HARDWARE).take(8)
            val id = "${Build.BRAND}-${Build.MODEL}-$serial".replace(" ", "-").lowercase()
            // URL-encode to be safe
            java.net.URLEncoder.encode(id, "UTF-8")
        } catch (_: Exception) {
            java.net.URLEncoder.encode(Build.MODEL ?: "unknown", "UTF-8")
        }
        val model = java.net.URLEncoder.encode(Build.MODEL ?: "unknown", "UTF-8")
        val brand = java.net.URLEncoder.encode(Build.BRAND ?: "unknown", "UTF-8")
        // Token is just "hermes" — relay no longer validates it (Tailscale auth)
        if (BuildConfig.DEBUG) Log.d(TAG, "Built WebSocket URL: $scheme://$base/ws?device_id=$deviceId")
        return "$scheme://$base/ws?token=hermes&device_id=$deviceId&model=$model&brand=$brand"
    }

    private suspend fun handleMessage(ws: WebSocket, text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject

            // Handle keepalive pings from relay — just ignore
            val msgType = json.get("type")?.asString
            if (msgType == "ping") return

            // Handle chat responses from Hermes
            if (msgType == "chat_response") {
                val chatText = json.get("text")?.asString ?: ""
                val cardsJson = json.get("cards")
                val cardsGson = if (cardsJson != null) gson.toJson(cardsJson) else "[]"
                if (chatText.isNotBlank()) {
                    AppLogger.i(TAG, "Chat response from Hermes: ${chatText.take(50)}")
                    notifyChatResponse(chatText, cardsGson)
                }
                return
            }

            val requestId = json.get("request_id")?.asString ?: ""
            val method = json.get("method")?.asString?.uppercase() ?: "GET"
            val path = json.get("path")?.asString ?: ""
            val params = json.getAsJsonObject("params") ?: JsonObject()
            val body = json.getAsJsonObject("body") ?: JsonObject()

            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Received command: $method $path (id=$requestId)")

            // Update voice state based on commands
            val voiceState = when {
                path.contains("/voice/start") || path.contains("/microphone") -> "listening"
                path.contains("/voice/stop") -> "idle"
                path.contains("/speak") || path.contains("/tts") -> "speaking"
                else -> null
            }
            voiceState?.let { notifyVoiceState(it) }

            // The relay connection is authenticated at connect time (token in the WS URL),
            // so commands arriving here are already authenticated.
            val response = CommandDispatcher.dispatch(method, path, params, body, authenticated = true)

            val responseJson = JsonObject().apply {
                addProperty("request_id", requestId)
                add("result", gson.toJsonTree(response.first))
                addProperty("status", response.second)
            }
            ws.send(responseJson.toString())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error handling message: ${e.message}", e)
            try {
                val json = JsonParser.parseString(text).asJsonObject
                val requestId = json.get("request_id")?.asString ?: ""
                val errorResponse = JsonObject().apply {
                    addProperty("request_id", requestId)
                    add("result", gson.toJsonTree(mapOf("error" to e.message)))
                    addProperty("status", 500)
                }
                ws.send(errorResponse.toString())
            } catch (_: Exception) {}
        }
    }

    /**
     * Handle binary audio data from the relay (TTS output).
     * Routes to the audio player for playback through the phone speaker.
     */
    private suspend fun handleBinaryMessage(bytes: okio.ByteString) {
        try {
            AudioPlayer.play(bytes.toByteArray())
        } catch (e: Exception) {
            AppLogger.w(TAG, "Audio playback error: ${e.message}")
        }
    }

    private fun notifyStatus(connected: Boolean, message: String) {
        val callback = onStatusChanged ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(connected, message)
            }
        } catch (_: Exception) {}
    }

    private fun notifyVoiceState(state: String) {
        val callback = onVoiceStateChanged ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(state)
            }
        } catch (_: Exception) {}
    }

    private fun notifyChatResponse(text: String, cardsJson: String) {
        val callback = onChatResponse ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(text, cardsJson)
            }
        } catch (_: Exception) {}
    }

    /** Called by VoiceService when speech transcripts are available. Thread-safe. */
    fun notifyTranscript(text: String, isFinal: Boolean) {
        val callback = onTranscript ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(text, isFinal)
            }
        } catch (_: Exception) {}
    }

    /** Called by VoiceService to report status or errors. Thread-safe. */
    fun notifyVoiceStatus(message: String, isError: Boolean) {
        val callback = onVoiceStatus ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(message, isError)
            }
        } catch (_: Exception) {}
    }

    // ── Foreground service for background persistence ────────────────────

    private fun startConnectionService() {
        val ctx = appContext ?: return
        try {
            val intent = android.content.Intent(ctx, com.hermesandroid.bridge.service.RelayConnectionService::class.java)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        ctx.startForegroundService(intent)
                    } else {
                        @Suppress("DEPRECATION")
                        ctx.startService(intent)
                    }
                    AppLogger.i(TAG, "ConnectionService started — app can be backgrounded safely")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to start ConnectionService: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to post ConnectionService start: ${e.message}")
        }
    }

    private fun stopConnectionService() {
        val ctx = appContext ?: return
        try {
            val intent = android.content.Intent(ctx, com.hermesandroid.bridge.service.RelayConnectionService::class.java)
            ctx.stopService(intent)
            AppLogger.i(TAG, "ConnectionService stopped")
        } catch (_: Exception) {}
    }
}
