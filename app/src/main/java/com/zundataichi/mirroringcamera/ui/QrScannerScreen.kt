package com.zundataichi.mirroringcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zundataichi.mirroringcamera.pairing.PairingClient
import com.zundataichi.mirroringcamera.pairing.PairingCredentials
import com.zundataichi.mirroringcamera.pairing.PairingPayload
import com.zundataichi.mirroringcamera.pairing.PairingResult
import com.zundataichi.mirroringcamera.pairing.QrCodeAnalyzer
import com.zundataichi.mirroringcamera.ui.theme.AccentYellow
import com.zundataichi.mirroringcamera.ui.theme.ConnectionGreen
import com.zundataichi.mirroringcamera.ui.theme.RecordingRed
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private sealed class ScanState {
    /** QR を探している。 */
    object Scanning : ScanState()

    /** 読み取れたので、サーバーにコードを引き換えに行っている。 */
    object Claiming : ScanState()

    data class Failed(val message: String) : ScanState()
    data class Succeeded(val credentials: PairingCredentials) : ScanState()
}

/**
 * 「QR コードで接続」の読み取り画面。
 *
 * CameraController の管理画面が出す QR を読み、[PairingClient] でこのカメラ専用の
 * API Key に引き換えて [onPaired] に渡す。撮影用のカメラとは別に、この画面の
 * ライフサイクルへ自前のプレビューと解析を bind する（離れるときに unbind し、
 * 撮影画面へ戻ったときに CameraManager が bind し直す）。
 */
@Composable
fun QrScannerScreen(
    cameraId: String,
    lifecycleOwner: LifecycleOwner,
    onPaired: (PairingCredentials) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairingClient = remember { PairingClient() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var scanState by remember { mutableStateOf<ScanState>(ScanState.Scanning) }

    fun handleScannedText(text: String) {
        // 引き換えている最中や、結果表示中のフレームは無視する。
        if (scanState !is ScanState.Scanning) return
        val payload = PairingPayload.parse(text) ?: return

        scanState = ScanState.Claiming
        scope.launch {
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            scanState = when (val result = pairingClient.claim(payload, cameraId, deviceName)) {
                is PairingResult.Success -> {
                    onPaired(result.credentials)
                    ScanState.Succeeded(result.credentials)
                }

                is PairingResult.Failure -> ScanState.Failed(result.message)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            CameraScannerPreview(
                enabled = scanState is ScanState.Scanning,
                onQrCodeDetected = { text -> handleScannedText(text) },
                lifecycleOwner = lifecycleOwner,
            )
        }

        // --- Scanning frame -------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "CameraController の管理画面に表示された\nQR コードを枠に合わせてください",
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )

            Box(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(0.72f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(3.dp, AccentYellow, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = scanState) {
                    is ScanState.Claiming -> StatusOverlay {
                        CircularProgressIndicator(color = AccentYellow)
                        Text(
                            text = "接続しています…",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }

                    is ScanState.Succeeded -> StatusOverlay {
                        Text(
                            text = "接続しました",
                            color = ConnectionGreen,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = state.credentials.displayName
                                ?: state.credentials.cameraId,
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = state.credentials.serverUrl,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    is ScanState.Failed -> StatusOverlay {
                        Text(
                            text = "⚠ ${state.message}",
                            color = RecordingRed,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { scanState = ScanState.Scanning },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentYellow),
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("もう一度読み取る", color = Color.Black)
                        }
                    }

                    ScanState.Scanning -> if (!hasCameraPermission) {
                        StatusOverlay {
                            Text(
                                text = "カメラの権限が必要です",
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                            Button(
                                onClick = {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentYellow),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                Text("権限を許可する", color = Color.Black)
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (scanState is ScanState.Succeeded) AccentYellow
                    else Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth(0.6f)
                    .height(48.dp),
            ) {
                Text(
                    text = if (scanState is ScanState.Succeeded) "完了" else "キャンセル",
                    color = if (scanState is ScanState.Succeeded) Color.Black else Color.White,
                )
            }
        }
    }
}

@Composable
private fun StatusOverlay(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun CameraScannerPreview(
    enabled: Boolean,
    onQrCodeDetected: (String) -> Unit,
    lifecycleOwner: LifecycleOwner,
) {
    val context = LocalContext.current
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    // 解析スレッドから読むので、常に最新の値を見せる。
    val detectedCallback = rememberUpdatedState(onQrCodeDetected)
    val isEnabled = rememberUpdatedState(enabled)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { previewView },
    )

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            val cameraProvider = try {
                providerFuture.get()
            } catch (_: Exception) {
                return@addListener
            }
            provider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        analysisExecutor,
                        QrCodeAnalyzer { text ->
                            if (isEnabled.value) {
                                ContextCompat.getMainExecutor(context).execute {
                                    detectedCallback.value(text)
                                }
                            }
                        },
                    )
                }

            try {
                // 撮影用のプレビューが bind されたままだとカメラを掴めない。
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (_: Exception) {
                // カメラを掴めなければプレビューは黒のまま。画面の説明文は残る。
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // 撮影画面に戻ると CameraManager が bind し直す。
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
}
