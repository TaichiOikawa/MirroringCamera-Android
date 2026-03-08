package com.zundataichi.mirroringcamera.manager

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class CaptureMode { PHOTO, VIDEO, TIMELAPSE }

data class CameraState(
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0,
    val isTimeLapseRunning: Boolean = false,
    val timeLapseCount: Int = 0,
    val timeLapseInterval: Double = 1.0,
    val timeLapseStartedAt: String = "",
    val currentResolution: String = "1080p",
    val currentFps: Int = 30,
    val supportedResolutions: List<String> = emptyList(),
    val supportedFps: List<Int> = emptyList(),
)

class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
    }

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var activeRecording: Recording? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var recordingTimer: Timer? = null
    private var timeLapseTimer: Timer? = null

    var onPreviewFrame: ((String) -> Unit)? = null
    var onTimeLapseProgress: ((Int) -> Unit)? = null
    var onShutterFlash: (() -> Unit)? = null

    // External display rendering
    var externalSurfaceHolder: android.view.SurfaceHolder? = null

    private var lastPreviewSentTime = 0L
    var previewIntervalMs: Long = 1000L

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases(lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD))
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processPreviewFrame(imageProxy)
                }
            }

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture,
                imageAnalysis
            )
            updateSupportedCapabilities()
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    private fun updateSupportedCapabilities() {
        val resolutions = mutableListOf<String>()
        resolutions.add("1080p")
        resolutions.add("720p")
        resolutions.add("480p")

        val fps = listOf(30, 60)

        _state.value = _state.value.copy(
            supportedResolutions = resolutions,
            supportedFps = fps
        )
    }

    // --- Photo ---

    fun capturePhoto(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val capture = imageCapture ?: run {
            onResult(false, "ImageCapture not initialized")
            return
        }

        onShutterFlash?.invoke()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MirroringCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo saved: ${output.savedUri}")
                    onResult(true, "photo saved")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    onResult(false, exception.message ?: "capture failed")
                }
            }
        )
    }

    // --- Video ---

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun toggleRecording(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_state.value.isRecording) {
            stopRecording(onResult)
        } else {
            startRecording(onResult)
        }
    }

    private fun startRecording(onResult: (Boolean, String) -> Unit) {
        val capture = videoCapture ?: run {
            onResult(false, "VideoCapture not initialized")
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MirroringCamera")
            }
        }

        val mediaStoreOutput = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val outputOptions = androidx.camera.video.MediaStoreOutputOptions.Builder(
            context.contentResolver,
            mediaStoreOutput
        ).setContentValues(contentValues).build()

        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        _state.value = _state.value.copy(isRecording = true, recordingDuration = 0)
                        startRecordingTimer()
                        onResult(true, "recording started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        _state.value = _state.value.copy(isRecording = false, recordingDuration = 0)
                        stopRecordingTimer()
                        if (event.hasError()) {
                            Log.e(TAG, "Recording error: ${event.error}")
                        } else {
                            Log.d(TAG, "Video saved: ${event.outputResults.outputUri}")
                        }
                    }
                }
            }
    }

    private fun stopRecording(onResult: (Boolean, String) -> Unit) {
        activeRecording?.stop()
        activeRecording = null
        onResult(true, "recording stopped")
    }

    private fun startRecordingTimer() {
        recordingTimer?.cancel()
        recordingTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    _state.value = _state.value.copy(
                        recordingDuration = _state.value.recordingDuration + 1
                    )
                }
            }, 1000, 1000)
        }
    }

    private fun stopRecordingTimer() {
        recordingTimer?.cancel()
        recordingTimer = null
    }

    // --- Timelapse ---

    fun startTimeLapse(intervalSec: Double, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_state.value.isTimeLapseRunning) {
            onResult(false, "timelapse already running")
            return
        }

        val startedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())

        _state.value = _state.value.copy(
            isTimeLapseRunning = true,
            timeLapseCount = 0,
            timeLapseInterval = intervalSec,
            timeLapseStartedAt = startedAt
        )

        timeLapseTimer = Timer().apply {
            val intervalMs = (intervalSec * 1000).toLong()
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    capturePhoto { success, _ ->
                        if (success) {
                            val newCount = _state.value.timeLapseCount + 1
                            _state.value = _state.value.copy(timeLapseCount = newCount)
                            onTimeLapseProgress?.invoke(newCount)
                        }
                    }
                }
            }, 0, intervalMs)
        }
        onResult(true, "timelapse started")
    }

    fun stopTimeLapse(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        timeLapseTimer?.cancel()
        timeLapseTimer = null
        val count = _state.value.timeLapseCount
        _state.value = _state.value.copy(isTimeLapseRunning = false)
        onResult(true, "timelapse stopped, $count photos taken")
    }

    // --- Mode ---

    fun setCaptureMode(mode: CaptureMode) {
        if (_state.value.isRecording || _state.value.isTimeLapseRunning) return
        _state.value = _state.value.copy(captureMode = mode)
    }

    // --- Preview frame processing ---

    private fun processPreviewFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                // Draw to external display every frame
                drawToExternalDisplay(bitmap)

                // Send to WebSocket at configured interval
                val now = System.currentTimeMillis()
                if (now - lastPreviewSentTime >= previewIntervalMs) {
                    lastPreviewSentTime = now
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 30, out)
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    onPreviewFrame?.invoke(base64)
                }
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Preview frame processing failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
        val jpegBytes = out.toByteArray()

        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        } else {
            bitmap
        }
    }

    private fun drawToExternalDisplay(bitmap: Bitmap) {
        val holder = externalSurfaceHolder ?: return
        try {
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(android.graphics.Color.BLACK)
                // Scale bitmap to fit the surface while maintaining aspect ratio
                val scaleX = canvas.width.toFloat() / bitmap.width
                val scaleY = canvas.height.toFloat() / bitmap.height
                val scale = minOf(scaleX, scaleY)
                val dx = (canvas.width - bitmap.width * scale) / 2f
                val dy = (canvas.height - bitmap.height * scale) / 2f
                val destRect = android.graphics.RectF(dx, dy, dx + bitmap.width * scale, dy + bitmap.height * scale)
                canvas.drawBitmap(bitmap, null, destRect, null)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "External display draw failed", e)
        }
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    fun shutdown() {
        recordingTimer?.cancel()
        timeLapseTimer?.cancel()
        cameraExecutor.shutdown()
    }
}
