package com.zundataichi.mirroringcamera.manager

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.zundataichi.mirroringcamera.data.SettingsStore
import com.zundataichi.mirroringcamera.ui.components.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 5000L
    }

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isAtemActive = MutableStateFlow(false)
    val isAtemActive: StateFlow<Boolean> = _isAtemActive.asStateFlow()

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .build()

    private var webSocket: WebSocket? = null
    private var statusTimer: Timer? = null
    private var reconnectAttempts = 0
    private var isIntentionalDisconnect = false

    /**
     * ソケットを開くたびに増える世代番号。
     *
     * close() は非同期なので、切断して即座に繋ぎ直すと（QR ペアリングで接続先が
     * 変わったときなど）古いソケットの onClosed が新しい接続の最中に届く。
     * 世代が違うコールバックは、もう誰も見ていないソケットからのものとして捨てる。
     */
    private var connectionGeneration = 0

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    var onCommandReceived: ((command: String, requestId: String, params: JsonObject?) -> Unit)? = null

    // WebRTC signaling relayed from the server (see docs/api.md §2-4).
    var onRtcSubscribe: ((controlId: String, sessionId: String) -> Unit)? = null
    var onRtcAnswer: ((sessionId: String, sdp: String) -> Unit)? = null
    var onRtcIce: ((sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit)? = null
    var onRtcUnsubscribe: ((sessionId: String) -> Unit)? = null
    var onRtcControlGone: ((controlId: String) -> Unit)? = null
    /** Invoked when the signaling socket drops; stale PeerConnections should be freed. */
    var onSignalingClosed: (() -> Unit)? = null

    fun connect() {
        if (_connectionStatus.value == ConnectionStatus.CONNECTED ||
            _connectionStatus.value == ConnectionStatus.CONNECTING) return

        val restUrl = settingsStore.getRestUrl()
        if (restUrl.isBlank()) {
            _lastError.value = "API URLが設定されていません"
            return
        }

        isIntentionalDisconnect = false
        reconnectAttempts = 0
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _lastError.value = null

        scope.launch {
            registerCamera(restUrl)
            openWebSocket()
        }
    }

    fun disconnect() {
        isIntentionalDisconnect = true
        connectionGeneration++
        stopStatusTimer()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isAtemActive.value = false
        onSignalingClosed?.invoke()
    }

    private suspend fun registerCamera(restUrl: String) {
        try {
            val cameraId = settingsStore.cameraID.value
            val body = buildJsonObject {
                put("camera_id", cameraId)
            }.toString()

            val apiKey = settingsStore.apiKey.value
            val requestBuilder = Request.Builder()
                .url("$restUrl/api/cameras/register")
                .post(body.toRequestBody("application/json".toMediaType()))
            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }
            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Camera registered successfully")
                } else {
                    Log.w(TAG, "Camera registration failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Camera registration error (continuing): ${e.message}")
        }
    }

    private fun openWebSocket() {
        val wsUrl = settingsStore.getWebSocketUrl()
        if (wsUrl.isBlank()) return

        val request = Request.Builder().url(wsUrl).build()
        val generation = ++connectionGeneration

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            /** この listener のソケットがまだ現役か。 */
            private fun isCurrent() = generation == connectionGeneration

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent()) {
                    // 開くのを待っている間に繋ぎ直された。掴んだままにしない。
                    webSocket.close(1000, "Superseded")
                    return
                }
                Log.d(TAG, "WebSocket connected")
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _lastError.value = null
                reconnectAttempts = 0
                startStatusTimer()
                sendTimeLapseStateIfRunning()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent()) return
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                if (!isCurrent()) return
                handleDisconnection()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                if (!isCurrent()) return
                _lastError.value = t.message
                handleDisconnection()
            }
        })
    }

    private fun handleDisconnection() {
        stopStatusTimer()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isAtemActive.value = false
        onSignalingClosed?.invoke()

        if (isIntentionalDisconnect) return

        reconnectAttempts++
        if (reconnectAttempts <= MAX_RECONNECT_ATTEMPTS) {
            scope.launch {
                Log.d(TAG, "Reconnecting (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")
                _connectionStatus.value = ConnectionStatus.CONNECTING
                delay(RECONNECT_DELAY_MS)
                openWebSocket()
            }
        } else {
            _lastError.value = "接続失敗（${MAX_RECONNECT_ATTEMPTS}回試行済み）。設定から再接続してください。"
        }
    }

    fun onForegroundResume() {
        val restUrl = settingsStore.getRestUrl()
        if (restUrl.isNotBlank() && _connectionStatus.value == ConnectionStatus.DISCONNECTED && !isIntentionalDisconnect) {
            reconnectAttempts = 0
            connect()
        }
    }

   fun sendStatus(cameraState: CameraState) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) return

        val batteryLevel = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100.0
        } catch (_: Exception) { 0.0 }

        val temperatureCelsius = getBatteryTemperature()
        val thermalState = getThermalState(temperatureCelsius)
        val network = getNetworkType()

        val json = buildJsonObject {
            put("type", "status")
            put("is_recording", cameraState.isRecording)
            put("battery_level", batteryLevel)
            put("temperature_thermal_state", thermalState)
            put("temperature_celsius", temperatureCelsius)
            put("platform", "android")
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("network", network)
        }.toString()

        webSocket?.send(json)
    }

    /**
     * Send a low-quality JPEG preview frame (base64) as the WebRTC fallback.
     * Only used while WebRTC cannot connect; see CameraController/docs/api.md
     * `preview`.
     */
    fun sendPreview(imageBase64: String) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) return
        val json = buildJsonObject {
            put("type", "preview")
            put("image_base64", imageBase64)
        }.toString()
        webSocket?.send(json)
    }

    // --- WebRTC signaling (camera -> server) ---

    fun sendRtcOffer(controlId: String, sessionId: String, sdp: String) {
        val json = buildJsonObject {
            put("type", "rtc_offer")
            put("control_id", controlId)
            put("session_id", sessionId)
            put("sdp", sdp)
        }.toString()
        webSocket?.send(json)
    }

    fun sendRtcIce(controlId: String, sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val json = buildJsonObject {
            put("type", "rtc_ice")
            put("control_id", controlId)
            put("session_id", sessionId)
            putJsonObject("candidate") {
                put("candidate", candidate)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
            }
        }.toString()
        webSocket?.send(json)
    }

    fun sendRtcClose(controlId: String, sessionId: String) {
        val json = buildJsonObject {
            put("type", "rtc_close")
            put("control_id", controlId)
            put("session_id", sessionId)
        }.toString()
        webSocket?.send(json)
    }

    fun sendCommandResult(requestId: String, success: Boolean, message: String) {
        val json = buildJsonObject {
            put("type", "command_result")
            put("request_id", requestId)
            put("success", success)
            put("message", message)
        }.toString()

        webSocket?.send(json)
    }

    fun sendTimeLapseEvent(type: String, extras: Map<String, Any> = emptyMap()) {
        val json = buildJsonObject {
            put("type", type)
            extras.forEach { (key, value) ->
                when (value) {
                    is Int -> put(key, value)
                    is Double -> put(key, value)
                    is Boolean -> put(key, value)
                    is String -> put(key, value)
                }
            }
        }.toString()

        webSocket?.send(json)
    }

    // --- Message Receiving ---

    private fun handleMessage(text: String) {
        try {
            val json = Json.decodeFromString<JsonObject>(text)
            val type = json["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "command" -> {
                    val command = json["command"]?.jsonPrimitive?.content ?: return
                    val requestId = json["request_id"]?.jsonPrimitive?.content ?: ""
                    onCommandReceived?.invoke(command, requestId, json)
                }
                "active_camera_state" -> {
                    val isActive = try {
                        json["is_active"]?.jsonPrimitive?.boolean ?: false
                    } catch (_: Exception) {
                        json["is_active"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    }
                    _isAtemActive.value = isActive
                }
                "rtc_subscribe" -> {
                    val controlId = json["control_id"]?.jsonPrimitive?.content ?: return
                    val sessionId = json["session_id"]?.jsonPrimitive?.content ?: return
                    onRtcSubscribe?.invoke(controlId, sessionId)
                }
                "rtc_answer" -> {
                    val sessionId = json["session_id"]?.jsonPrimitive?.content ?: return
                    val sdp = json["sdp"]?.jsonPrimitive?.content ?: return
                    onRtcAnswer?.invoke(sessionId, sdp)
                }
                "rtc_ice" -> {
                    val sessionId = json["session_id"]?.jsonPrimitive?.content ?: return
                    val cand = json["candidate"]?.jsonObject ?: return
                    val candidate = cand["candidate"]?.jsonPrimitive?.contentOrNull ?: return
                    val sdpMid = cand["sdpMid"]?.jsonPrimitive?.contentOrNull
                    val sdpMLineIndex = cand["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
                    onRtcIce?.invoke(sessionId, sdpMid, sdpMLineIndex, candidate)
                }
                "rtc_unsubscribe" -> {
                    val sessionId = json["session_id"]?.jsonPrimitive?.content ?: return
                    onRtcUnsubscribe?.invoke(sessionId)
                }
                "rtc_control_gone" -> {
                    val controlId = json["control_id"]?.jsonPrimitive?.content ?: return
                    onRtcControlGone?.invoke(controlId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    // --- Status Timer ---

    private var currentCameraState: CameraState? = null

    fun updateCameraState(state: CameraState) {
        currentCameraState = state
    }

    private fun startStatusTimer() {
        stopStatusTimer()
        statusTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    currentCameraState?.let { sendStatus(it) }
                }
            }, 0, 1000)
        }
    }

    private fun stopStatusTimer() {
        statusTimer?.cancel()
        statusTimer = null
    }

    private fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
        } catch (_: Exception) { "unknown" }
    }

    private fun getBatteryTemperature(): Double {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0
        } catch (_: Exception) { 0.0 }
    }

    private fun getThermalState(temperatureCelsius: Double): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT -> "nominal"
                    PowerManager.THERMAL_STATUS_MODERATE -> "fair"
                    PowerManager.THERMAL_STATUS_SEVERE -> "serious"
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
                    else -> "nominal"
                }
            } catch (_: Exception) { "nominal" }
        }
        // API 28以下: バッテリー温度から推定
        return when {
            temperatureCelsius < 35.0 -> "nominal"
            temperatureCelsius < 40.0 -> "fair"
            temperatureCelsius < 45.0 -> "serious"
            else -> "critical"
        }
    }

    fun shutdown() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }

    /** 再接続時にタイムラプス実行中なら状態同期メッセージを送信 */
    private fun sendTimeLapseStateIfRunning() {
        val state = currentCameraState ?: return
        if (!state.isTimeLapseRunning) return

        sendTimeLapseEvent("timelapse_state", mapOf(
            "is_running" to true,
            "interval_sec" to state.timeLapseInterval,
            "shot_count" to state.timeLapseCount,
            "started_at" to state.timeLapseStartedAt,
        ))
    }

    /** 撮影ごとの timelapse_progress 送信 */
    fun sendTimeLapseProgress(shotCount: Int) {
        sendTimeLapseEvent("timelapse_progress", mapOf("shot_count" to shotCount))
    }
}
