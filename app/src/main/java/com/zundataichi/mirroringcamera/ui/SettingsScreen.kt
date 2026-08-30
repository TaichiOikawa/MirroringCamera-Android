package com.zundataichi.mirroringcamera.ui

import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zundataichi.mirroringcamera.data.SettingsStore
import com.zundataichi.mirroringcamera.ui.components.ConnectionIndicator
import com.zundataichi.mirroringcamera.ui.components.ConnectionStatus
import com.zundataichi.mirroringcamera.ui.theme.AccentYellow
import com.zundataichi.mirroringcamera.ui.theme.ConnectionGreen
import com.zundataichi.mirroringcamera.ui.theme.ConnectionOrange
import com.zundataichi.mirroringcamera.ui.theme.ConnectionRed
import com.zundataichi.mirroringcamera.ui.theme.RecordingRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    connectionStatus: ConnectionStatus,
    lastError: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val apiBaseURL by settingsStore.apiBaseURL.collectAsState()
    val apiKey by settingsStore.apiKey.collectAsState()
    val cameraID by settingsStore.cameraID.collectAsState()
    val previewInterval by settingsStore.previewInterval.collectAsState()
    var urlInput by remember(apiBaseURL) { mutableStateOf(apiBaseURL) }
    var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val batteryLevel = remember {
        try {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) { -1 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                },
                actions = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = AccentYellow)
                    ) {
                        Text("完了")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // --- API URL Section ---
            SectionHeader("CameraController API URL")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("URL") },
                placeholder = { Text("http://192.168.1.10:3001") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "FastAPI サーバーのアドレスを入力してください",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            // Save URL when changed
            if (urlInput != apiBaseURL) {
                Button(
                    onClick = { settingsStore.setApiBaseURL(urlInput) },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentYellow)
                ) {
                    Text("保存", color = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- API Key Section ---
            SectionHeader("Camera API Key")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("API Key") },
                placeholder = { Text("WebコントロールUIから発行されたキー") },
                singleLine = true,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = "WebSocket接続時の認証に使用します",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { apiKeyVisible = !apiKeyVisible },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentYellow)
                ) {
                    Text(if (apiKeyVisible) "隠す" else "表示", fontSize = 12.sp)
                }
            }
            if (apiKeyInput != apiKey) {
                Button(
                    onClick = { settingsStore.setApiKey(apiKeyInput) },
                    modifier = Modifier.padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentYellow)
                ) {
                    Text("保存", color = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // --- Connection Section ---
            SectionHeader("接続")
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("状態", color = Color.White, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(12.dp))
                ConnectionIndicator(
                    status = connectionStatus,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> "接続済み"
                        ConnectionStatus.CONNECTING -> "接続中..."
                        ConnectionStatus.DISCONNECTED -> "未接続"
                    },
                    color = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> ConnectionGreen
                        ConnectionStatus.CONNECTING -> ConnectionOrange
                        ConnectionStatus.DISCONNECTED -> ConnectionRed
                    },
                    fontSize = 14.sp
                )
            }

            if (!lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ $lastError",
                    color = RecordingRed,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    if (connectionStatus == ConnectionStatus.CONNECTED) onDisconnect()
                    else onConnect()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connectionStatus == ConnectionStatus.CONNECTED)
                        RecordingRed else AccentYellow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (connectionStatus == ConnectionStatus.CONNECTED) "切断" else "接続",
                    color = if (connectionStatus == ConnectionStatus.CONNECTED) Color.White else Color.Black
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // --- Preview Section ---
            SectionHeader("プレビュー送信（WebRTC不通時）")
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("送信間隔", color = Color.White, fontSize = 14.sp)
                Spacer(modifier = Modifier.weight(1f))
                val intervals = listOf(0.5, 1.0, 1.5, 2.0)
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    intervals.forEach { interval ->
                        val isSelected = previewInterval == interval
                        Button(
                            onClick = { settingsStore.setPreviewInterval(interval) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) AccentYellow else Color.White.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                "${interval}秒",
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // --- Camera Info Section ---
            SectionHeader("カメラ情報")
            Spacer(modifier = Modifier.height(6.dp))
            InfoRow("Camera ID", cameraID)
            InfoRow("デバイス", "${Build.MANUFACTURER} ${Build.MODEL}")
            if (batteryLevel >= 0) {
                InfoRow("バッテリー", "${batteryLevel}%")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            // --- Credit Section ---
            SectionHeader("クレジット")
            Spacer(modifier = Modifier.height(6.dp))
            InfoRow("Build ID", com.zundataichi.mirroringcamera.BuildConfig.BUILD_TIME)
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Text("GitHub", color = Color.White, fontSize = 14.sp)
                Text(
                    text = "TaichiOikawa/MirroringCamera-Android",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/TaichiOikawa/MirroringCamera-Android")
                    }
                )
            }
            InfoRow("", "© 2026 Taichi Oikawa")
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Text(value, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}
