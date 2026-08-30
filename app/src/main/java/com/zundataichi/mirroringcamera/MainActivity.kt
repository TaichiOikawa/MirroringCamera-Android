package com.zundataichi.mirroringcamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zundataichi.mirroringcamera.data.SettingsStore
import com.zundataichi.mirroringcamera.manager.CameraManager
import com.zundataichi.mirroringcamera.manager.CaptureMode
import com.zundataichi.mirroringcamera.manager.OrientationManager
import com.zundataichi.mirroringcamera.manager.WebSocketManager
import com.zundataichi.mirroringcamera.ui.ExternalDisplayManager
import com.zundataichi.mirroringcamera.ui.MainScreen
import com.zundataichi.mirroringcamera.ui.SettingsScreen
import com.zundataichi.mirroringcamera.ui.theme.MirroringCameraTheme
import com.zundataichi.mirroringcamera.util.VolumeButtonShutter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var cameraManager: CameraManager
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var webRtcManager: com.zundataichi.mirroringcamera.manager.WebRtcManager
    private lateinit var orientationManager: OrientationManager
    private lateinit var externalDisplayManager: ExternalDisplayManager
    private val volumeButtonShutter = VolumeButtonShutter()

    private var showSettings by mutableStateOf(false)
    private var previewView: PreviewView? = null

    private var showTimeLapseIntervalDialog by mutableStateOf(false)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted && audioGranted) {
            startCameraIfReady()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fullscreen (hide system bars)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Get app-scoped managers (survive rotation)
        val app = application as MirroringCameraApp
        settingsStore = app.settingsStore
        webSocketManager = app.webSocketManager
        webRtcManager = app.webRtcManager

        // Initialize activity-scoped managers
        cameraManager = CameraManager(this)
        orientationManager = OrientationManager()
        externalDisplayManager = ExternalDisplayManager(this)

        // Setup volume shutter
        volumeButtonShutter.onShutterPress = { handleShutterAction() }

        // Setup WebSocket command handling
        webSocketManager.onCommandReceived = { command, requestId, params ->
            runOnUiThread { handleCommand(command, requestId, params) }
        }

        // Feed camera frames to the WebRTC encoder
        cameraManager.onFrameForWebRtc = { imageProxy ->
            webRtcManager.pushFrame(imageProxy)
        }

        // JPEG fallback: send periodic preview frames only while WebRTC is down.
        cameraManager.isFallbackActive = { app.webRtcFallbackActive }
        cameraManager.fallbackIntervalProvider = { settingsStore.previewInterval.value }
        cameraManager.onFallbackPreview = { imageBase64 ->
            webSocketManager.sendPreview(imageBase64)
        }

        // Connect timelapse progress to WebSocket
        cameraManager.onTimeLapseProgress = { shotCount ->
            webSocketManager.sendTimeLapseProgress(shotCount)
        }

        // Sync camera state to WebSocket
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                cameraManager.state.collect { state ->
                    webSocketManager.updateCameraState(state)
                }
            }
        }

        // Auto-connect if URL is configured and not already connected
        if (settingsStore.apiBaseURL.value.isNotBlank() &&
            webSocketManager.connectionStatus.value == com.zundataichi.mirroringcamera.ui.components.ConnectionStatus.DISCONNECTED) {
            webSocketManager.connect()
        }

        // Start external display manager and connect to camera
        externalDisplayManager.onSurfaceAvailable = { holder ->
            cameraManager.externalSurfaceHolder = holder
        }
        externalDisplayManager.onSurfaceDestroyed = {
            cameraManager.externalSurfaceHolder = null
        }
        externalDisplayManager.start()

        // Request camera permissions
        checkAndRequestPermissions()

        setContent {
            MirroringCameraTheme {
                val connectionStatus by webSocketManager.connectionStatus.collectAsState()
                val isAtemActive by webSocketManager.isAtemActive.collectAsState()
                val isOrientationLocked by orientationManager.isLocked.collectAsState()
                val isExternalDisplayConnected by externalDisplayManager.isConnected.collectAsState()
                val lastError by webSocketManager.lastError.collectAsState()

                if (showSettings) {
                    SettingsScreen(
                        settingsStore = settingsStore,
                        connectionStatus = connectionStatus,
                        lastError = lastError,
                        onConnect = { webSocketManager.connect() },
                        onDisconnect = { webSocketManager.disconnect() },
                        onDismiss = { showSettings = false },
                    )
                } else {
                    MainScreen(
                        cameraManager = cameraManager,
                        connectionStatus = connectionStatus,
                        isAtemActive = isAtemActive,
                        isOrientationLocked = isOrientationLocked,
                        isExternalDisplayConnected = isExternalDisplayConnected,
                        onSettingsClick = { showSettings = true },
                        onLockToggle = { orientationManager.toggleLock(this@MainActivity) },
                        onShutterClick = { handleShutterAction() },
                        onModeSelected = { cameraManager.setCaptureMode(it) },
                        onResolutionClick = { /* TODO: show resolution picker */ },
                        onFpsClick = { /* TODO: show FPS picker */ },
                        onPreviewViewCreated = { pv ->
                            previewView = pv
                            startCameraIfReady()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Timelapse interval dialog
                if (showTimeLapseIntervalDialog) {
                    TimeLapseIntervalDialog(
                        onIntervalSelected = { interval ->
                            showTimeLapseIntervalDialog = false
                            cameraManager.startTimeLapse(interval)
                        },
                        onDismiss = { showTimeLapseIntervalDialog = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webSocketManager.onForegroundResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop pushing frames; the WebRtcManager is app-scoped and survives this Activity.
        cameraManager.onFrameForWebRtc = null
        cameraManager.shutdown()
        externalDisplayManager.stop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeButtonShutter.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun checkAndRequestPermissions() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted && audioGranted) {
            startCameraIfReady()
        } else {
            cameraPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun startCameraIfReady() {
        val pv = previewView ?: return
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) return
        cameraManager.startCamera(this, pv)
    }

    private fun handleShutterAction() {
        val state = cameraManager.state.value
        when (state.captureMode) {
            CaptureMode.PHOTO -> cameraManager.capturePhoto()
            CaptureMode.VIDEO -> cameraManager.toggleRecording()
            CaptureMode.TIMELAPSE -> {
                if (state.isTimeLapseRunning) {
                    cameraManager.stopTimeLapse()
                } else {
                    showTimeLapseIntervalDialog = true
                }
            }
        }
    }

    private fun handleCommand(command: String, requestId: String, params: JsonObject?) {
        when (command) {
            "take_photo" -> {
                cameraManager.setCaptureMode(CaptureMode.PHOTO)
                cameraManager.capturePhoto { success, message ->
                    webSocketManager.sendCommandResult(requestId, success, message)
                }
            }
            "recording_start" -> {
                cameraManager.setCaptureMode(CaptureMode.VIDEO)
                if (!cameraManager.state.value.isRecording) {
                    cameraManager.toggleRecording { success, message ->
                        webSocketManager.sendCommandResult(requestId, success, message)
                    }
                } else {
                    webSocketManager.sendCommandResult(requestId, true, "already recording")
                }
            }
            "recording_stop" -> {
                if (cameraManager.state.value.isRecording) {
                    cameraManager.toggleRecording { success, message ->
                        webSocketManager.sendCommandResult(requestId, success, message)
                    }
                } else {
                    webSocketManager.sendCommandResult(requestId, true, "not recording")
                }
            }
            "timelapse_start" -> {
                val intervalSec = params?.get("interval_sec")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0
                cameraManager.setCaptureMode(CaptureMode.TIMELAPSE)
                cameraManager.startTimeLapse(intervalSec) { success, message ->
                    webSocketManager.sendCommandResult(requestId, success, message)
                    if (success) {
                        val state = cameraManager.state.value
                        webSocketManager.sendTimeLapseEvent("timelapse_started", mapOf(
                            "interval_sec" to intervalSec,
                            "started_at" to state.timeLapseStartedAt
                        ))
                    }
                }
            }
            "timelapse_stop" -> {
                cameraManager.stopTimeLapse { success, message ->
                    webSocketManager.sendCommandResult(requestId, success, message)
                    webSocketManager.sendTimeLapseEvent("timelapse_stopped", mapOf(
                        "shot_count" to cameraManager.state.value.timeLapseCount
                    ))
                }
            }
            "timelapse_state_request" -> {
                val state = cameraManager.state.value
                webSocketManager.sendTimeLapseEvent("timelapse_state", mapOf(
                    "is_running" to state.isTimeLapseRunning,
                    "interval_sec" to state.timeLapseInterval,
                    "shot_count" to state.timeLapseCount,
                    "started_at" to state.timeLapseStartedAt,
                ))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TimeLapseIntervalDialog(
    onIntervalSelected: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val intervals = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("撮影間隔") },
        text = {
            androidx.compose.foundation.layout.Column {
                intervals.forEach { interval ->
                    TextButton(onClick = { onIntervalSelected(interval) }) {
                        Text("${interval}秒")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}