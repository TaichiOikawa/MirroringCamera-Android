package com.zundataichi.mirroringcamera.pairing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX のフレームから QR コードを読む ImageAnalysis。
 * 実際の解読は [QrLuminanceDecoder]（Android 非依存）に任せる。
 */
class QrCodeAnalyzer(
    private val onQrCodeDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    // analyze() は解析用の単一スレッドから呼ばれるので、使い回して問題ない。
    private val decoder = QrLuminanceDecoder()

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull()
            if (plane != null) {
                val buffer = plane.buffer
                val luminance = ByteArray(buffer.remaining())
                buffer.get(luminance)

                val text = decoder.decode(
                    luminance = luminance,
                    rowStride = plane.rowStride,
                    width = image.width,
                    height = image.height,
                )
                if (text != null) onQrCodeDetected(text)
            }
        } catch (_: Exception) {
            // 壊れたフレームで解析を止めない。
        } finally {
            image.close()
        }
    }
}
