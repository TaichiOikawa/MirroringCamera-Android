package com.zundataichi.mirroringcamera.ui

import android.content.res.Configuration
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zundataichi.mirroringcamera.manager.CameraManager
import com.zundataichi.mirroringcamera.manager.CameraState
import com.zundataichi.mirroringcamera.manager.CaptureMode
import com.zundataichi.mirroringcamera.ui.components.ConnectionIndicator
import com.zundataichi.mirroringcamera.ui.components.ConnectionStatus
import com.zundataichi.mirroringcamera.ui.components.ModeSelector
import com.zundataichi.mirroringcamera.ui.components.ShutterButton
import com.zundataichi.mirroringcamera.ui.theme.AccentYellow
import com.zundataichi.mirroringcamera.ui.theme.AtemRedBorder
import com.zundataichi.mirroringcamera.ui.theme.ConnectionGreen
import com.zundataichi.mirroringcamera.ui.theme.RecordingRed
import com.zundataichi.mirroringcamera.ui.theme.TimeLapseOrange

@Composable
fun MainScreen(
    cameraManager: CameraManager,
    connectionStatus: ConnectionStatus,
    isAtemActive: Boolean,
    isOrientationLocked: Boolean,
    isExternalDisplayConnected: Boolean,
    onSettingsClick: () -> Unit,
    onLockToggle: () -> Unit,
    onShutterClick: () -> Unit,
    onModeSelected: (CaptureMode) -> Unit,
    onResolutionClick: () -> Unit,
    onFpsClick: () -> Unit,
    onPreviewViewCreated: (PreviewView) -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraState by cameraManager.state.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val shutterAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Flash effect triggered by CameraManager (works for both local and remote capture)
    androidx.compose.runtime.DisposableEffect(cameraManager) {
        cameraManager.onShutterFlash = {
            scope.launch {
                shutterAlpha.snapTo(1f)
                shutterAlpha.animateTo(0f, androidx.compose.animation.core.tween(150))
            }
        }
        onDispose { cameraManager.onShutterFlash = null }
    }

    Box(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
        // Camera preview (16:9 letterboxed with black background)
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            scaleType = PreviewView.ScaleType.FIT_CENTER,
            onPreviewViewCreated = onPreviewViewCreated
        )

        // Shutter flash overlay
        if (shutterAlpha.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = shutterAlpha.value))
            )
        }

        // ATEM red border
        if (isAtemActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(6.dp, AtemRedBorder)
            )
        }

        // Controls overlay
        if (isLandscape) {
            LandscapeControls(
                cameraState = cameraState,
                connectionStatus = connectionStatus,
                isOrientationLocked = isOrientationLocked,
                isExternalDisplayConnected = isExternalDisplayConnected,
                onSettingsClick = onSettingsClick,
                onLockToggle = onLockToggle,
                onShutterClick = onShutterClick,
                onModeSelected = onModeSelected,
                onResolutionClick = onResolutionClick,
                onFpsClick = onFpsClick,
                cameraManager = cameraManager,
            )
        } else {
            PortraitControls(
                cameraState = cameraState,
                connectionStatus = connectionStatus,
                isOrientationLocked = isOrientationLocked,
                isExternalDisplayConnected = isExternalDisplayConnected,
                onSettingsClick = onSettingsClick,
                onLockToggle = onLockToggle,
                onShutterClick = onShutterClick,
                onModeSelected = onModeSelected,
                onResolutionClick = onResolutionClick,
                onFpsClick = onFpsClick,
                cameraManager = cameraManager,
            )
        }
    }
}

@Composable
private fun PortraitControls(
    cameraState: CameraState,
    connectionStatus: ConnectionStatus,
    isOrientationLocked: Boolean,
    isExternalDisplayConnected: Boolean,
    onSettingsClick: () -> Unit,
    onLockToggle: () -> Unit,
    onShutterClick: () -> Unit,
    onModeSelected: (CaptureMode) -> Unit,
    onResolutionClick: () -> Unit,
    onFpsClick: () -> Unit,
    cameraManager: CameraManager,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cameraState.currentResolution,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onResolutionClick() }
                )
                Text(
                    text = "${cameraState.currentFps}fps",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onFpsClick() }
                )
                if (isExternalDisplayConnected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = "External display",
                            tint = ConnectionGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("出力中", color = ConnectionGreen, fontSize = 12.sp)
                    }
                }
                DeviceInfoDisplay()
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
                ConnectionIndicator(status = connectionStatus)
                IconButton(onClick = onLockToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isOrientationLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = "Orientation lock",
                        tint = if (isOrientationLocked) AccentYellow else Color.White
                    )
                }
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status
            StatusDisplay(cameraState = cameraState, cameraManager = cameraManager)

            Spacer(modifier = Modifier.height(16.dp))

            // Mode selector
            ModeSelector(
                currentMode = cameraState.captureMode,
                isRecording = cameraState.isRecording,
                isTimeLapseRunning = cameraState.isTimeLapseRunning,
                onModeSelected = onModeSelected,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Shutter button
            ShutterButton(
                captureMode = cameraState.captureMode,
                isRecording = cameraState.isRecording,
                isTimeLapseRunning = cameraState.isTimeLapseRunning,
                onClick = onShutterClick,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LandscapeControls(
    cameraState: CameraState,
    connectionStatus: ConnectionStatus,
    isOrientationLocked: Boolean,
    isExternalDisplayConnected: Boolean,
    onSettingsClick: () -> Unit,
    onLockToggle: () -> Unit,
    onShutterClick: () -> Unit,
    onModeSelected: (CaptureMode) -> Unit,
    onResolutionClick: () -> Unit,
    onFpsClick: () -> Unit,
    cameraManager: CameraManager,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left panel
        Column(
            modifier = Modifier
                .width(120.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = cameraState.currentResolution,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onResolutionClick() }
                )
                Text(
                    text = "${cameraState.currentFps}fps",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onFpsClick() }
                )
                IconButton(onClick = onLockToggle, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (isOrientationLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = "Orientation lock",
                        tint = if (isOrientationLocked) AccentYellow else Color.White
                    )
                }
                if (isExternalDisplayConnected) {
                    Icon(
                        Icons.Filled.Tv,
                        contentDescription = "External display",
                        tint = ConnectionGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
                DeviceInfoDisplay(isVertical = true)
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
                ConnectionIndicator(status = connectionStatus)
            }

            // Mode selector (vertical)
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CaptureMode.entries.forEach { mode ->
                    val isSelected = mode == cameraState.captureMode
                    val isDisabled = when {
                        cameraState.isRecording && mode != CaptureMode.VIDEO -> true
                        cameraState.isTimeLapseRunning && mode != CaptureMode.TIMELAPSE -> true
                        else -> false
                    }
                    Text(
                        text = when (mode) {
                            CaptureMode.PHOTO -> "写真"
                            CaptureMode.VIDEO -> "ビデオ"
                            CaptureMode.TIMELAPSE -> "ﾀｲﾑﾗﾌﾟｽ"
                        },
                        color = when {
                            isDisabled -> Color.White.copy(alpha = 0.3f)
                            isSelected -> AccentYellow
                            else -> Color.White.copy(alpha = 0.5f)
                        },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(enabled = !isDisabled) {
                            onModeSelected(mode)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Right panel
        Column(
            modifier = Modifier
                .width(120.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusDisplay(cameraState = cameraState, cameraManager = cameraManager)

            Spacer(modifier = Modifier.height(24.dp))

            ShutterButton(
                captureMode = cameraState.captureMode,
                isRecording = cameraState.isRecording,
                isTimeLapseRunning = cameraState.isTimeLapseRunning,
                onClick = onShutterClick,
            )
        }
    }
}

@Composable
private fun StatusDisplay(
    cameraState: CameraState,
    cameraManager: CameraManager,
    modifier: Modifier = Modifier
) {
    when {
        cameraState.isRecording -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(RecordingRed, shape = androidx.compose.foundation.shape.CircleShape)
                )
                Text(
                    text = "REC ${cameraManager.formatDuration(cameraState.recordingDuration)}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        cameraState.isTimeLapseRunning -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = TimeLapseOrange,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${cameraState.timeLapseCount} 枚",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoDisplay(
    isVertical: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var batteryLevel by remember { mutableStateOf(-1) }
    var thermalState by remember { mutableStateOf("nominal") }
    var temperatureCelsius by remember { mutableStateOf(0.0) }

    // 5秒間隔で端末情報を更新
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            try {
                val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (_: Exception) {}

            try {
                val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                temperatureCelsius = temp / 10.0
            } catch (_: Exception) {}

            thermalState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    when (pm.currentThermalStatus) {
                        android.os.PowerManager.THERMAL_STATUS_NONE,
                        android.os.PowerManager.THERMAL_STATUS_LIGHT -> "nominal"
                        android.os.PowerManager.THERMAL_STATUS_MODERATE -> "fair"
                        android.os.PowerManager.THERMAL_STATUS_SEVERE -> "serious"
                        android.os.PowerManager.THERMAL_STATUS_CRITICAL,
                        android.os.PowerManager.THERMAL_STATUS_EMERGENCY,
                        android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
                        else -> "nominal"
                    }
                } catch (_: Exception) { "nominal" }
            } else {
                when {
                    temperatureCelsius < 35.0 -> "nominal"
                    temperatureCelsius < 40.0 -> "fair"
                    temperatureCelsius < 45.0 -> "serious"
                    else -> "critical"
                }
            }

            delay(5000)
        }
    }

    val batteryColor = when {
        batteryLevel <= 20 -> RecordingRed
        batteryLevel <= 50 -> AccentYellow
        else -> ConnectionGreen
    }

    val thermalColor = when (thermalState) {
        "nominal" -> ConnectionGreen
        "fair" -> AccentYellow
        "serious" -> TimeLapseOrange
        "critical" -> RecordingRed
        else -> Color.White
    }

    val batteryItem: @Composable () -> Unit = {
        if (batteryLevel >= 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = when {
                        batteryLevel > 90 -> Icons.Filled.BatteryFull
                        batteryLevel > 50 -> Icons.Filled.Battery5Bar
                        batteryLevel > 20 -> Icons.Filled.Battery3Bar
                        else -> Icons.Filled.Battery1Bar
                    },
                    contentDescription = "Battery",
                    tint = batteryColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "${batteryLevel}%",
                    color = batteryColor,
                    fontSize = 11.sp
                )
            }
        }
    }

    val temperatureItem: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Thermostat,
                contentDescription = "Temperature",
                tint = thermalColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "${"%.1f".format(temperatureCelsius)}°",
                color = thermalColor,
                fontSize = 11.sp
            )
        }
    }

    if (isVertical) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            batteryItem()
            temperatureItem()
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            batteryItem()
            temperatureItem()
        }
    }
}
