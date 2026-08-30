package com.zundataichi.mirroringcamera.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrLuminanceDecoderTest {

    private val payloadText =
        """{"v":1,"type":"camera-pairing","server_url":"http://192.168.1.10:3001",""" +
            """"code":"K7F3A9QMX2","expires_at":"2026-08-30T06:00:00+00:00"}"""

    @Test
    fun `reads a QR rendered exactly like the admin page's`() {
        val frame = renderQrToLuminance(payloadText, size = 480, rowStride = 480)
        val decoded = QrLuminanceDecoder().decode(frame, 480, 480, 480)
        assertEquals(payloadText, decoded)
    }

    @Test
    fun `reads a frame whose rows are padded by the camera`() {
        // CameraX often hands back a Y plane with rowStride > width.
        val rowStride = 512
        val frame = renderQrToLuminance(payloadText, size = 480, rowStride = rowStride)
        val decoded = QrLuminanceDecoder().decode(frame, rowStride, 480, 480)
        assertEquals(payloadText, decoded)
    }

    @Test
    fun `reads an inverted QR`() {
        val frame = renderQrToLuminance(payloadText, size = 480, rowStride = 480, invert = true)
        val decoded = QrLuminanceDecoder().decode(frame, 480, 480, 480)
        assertEquals(payloadText, decoded)
    }

    @Test
    fun `returns null for a frame with no QR in it`() {
        val blank = ByteArray(480 * 480) { -1 }
        assertNull(QrLuminanceDecoder().decode(blank, 480, 480, 480))
    }

    @Test
    fun `returns null instead of throwing on a truncated frame`() {
        assertNull(QrLuminanceDecoder().decode(ByteArray(10), 480, 480, 480))
        assertNull(QrLuminanceDecoder().decode(ByteArray(0), 0, 0, 0))
    }

    /** Draw a QR and flatten it into a Y-plane byte array (0 = black, 255 = white). */
    private fun renderQrToLuminance(
        text: String,
        size: Int,
        rowStride: Int,
        invert: Boolean = false,
    ): ByteArray {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
            ),
        )
        val luminance = ByteArray(rowStride * size) { 0x7F }  // padding: mid grey
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dark = matrix.get(x, y)
                val value = if (dark != invert) 0x00 else 0xFF
                luminance[y * rowStride + x] = value.toByte()
            }
        }
        return luminance
    }
}
