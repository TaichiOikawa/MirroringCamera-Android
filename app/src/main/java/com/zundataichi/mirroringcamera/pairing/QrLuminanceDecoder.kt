package com.zundataichi.mirroringcamera.pairing

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * 輝度（Y プレーン）のバイト列から QR を読む。
 *
 * QR は白黒なので色情報は要らず、YUV→RGB 変換を丸ごと省ける。
 * ZXing の Reader はスレッドセーフではないので、1 スレッドにつき 1 インスタンス。
 */
class QrLuminanceDecoder {

    private val reader = QRCodeReader()
    private val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)

    /**
     * @param luminance Y プレーンのバイト列
     * @param rowStride 1 行あたりのバイト数。カメラは行末にパディングを入れることが
     *   あるので [width] とは限らない
     * @param width 実際の画素幅
     * @param height 画素の高さ
     * @return 読み取れた文字列。読めなければ null
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (rowStride <= 0 || width <= 0 || height <= 0) return null
        if (luminance.size < rowStride * height) return null

        val source = PlanarYUVLuminanceSource(
            luminance,
            rowStride,
            height,
            0,
            0,
            width.coerceAtMost(rowStride),
            height,
            false,
        )

        // 白地に黒が基本だが、反転表示の画面を写すこともあるので両方試す。
        return decodeSource(source) ?: decodeSource(source.invert())
    }

    private fun decodeSource(source: LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: Exception) {
        // 読めないフレームは珍しくない。次のフレームで拾う。
        null
    } finally {
        reader.reset()
    }
}
