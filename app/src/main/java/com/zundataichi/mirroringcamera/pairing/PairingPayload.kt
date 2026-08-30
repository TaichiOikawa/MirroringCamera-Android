package com.zundataichi.mirroringcamera.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * QR コードに埋め込まれている接続情報（CameraController docs/api.md §0-7）。
 *
 * ```json
 * {"v":1,"type":"camera-pairing","server_url":"http://...","code":"K7F3A9QMX2","expires_at":"..."}
 * ```
 */
data class PairingPayload(
    val serverUrl: String,
    val code: String,
    val expiresAt: String?,
) {
    companion object {
        const val TYPE = "camera-pairing"
        const val SUPPORTED_VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 読み取った文字列を解釈する。CameraController の QR でなければ null。
         *
         * カメラは無関係なバーコードも拾ってしまうので、`type` と `v` を確認できた
         * ものだけを受け付ける。
         */
        fun parse(scannedText: String): PairingPayload? {
            val root = try {
                json.parseToJsonElement(scannedText.trim()).jsonObject
            } catch (_: Exception) {
                return null
            }

            if (root["type"]?.jsonPrimitive?.contentOrNull != TYPE) return null
            if (root["v"]?.jsonPrimitive?.intOrNull != SUPPORTED_VERSION) return null

            val serverUrl = normalizeServerUrl(
                root["server_url"]?.jsonPrimitive?.contentOrNull ?: return null
            )
            if (serverUrl.isBlank()) return null

            val code = normalizeCode(root["code"]?.jsonPrimitive?.contentOrNull ?: return null)
            if (code.isBlank()) return null

            return PairingPayload(
                serverUrl = serverUrl,
                code = code,
                expiresAt = root["expires_at"]?.jsonPrimitive?.contentOrNull,
            )
        }

        /** スキームが省略されていても繋がるようにし、末尾の / は落とす。 */
        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }

        /** 表示用の区切り（K7F3A-9QMX2）を落として本来のコードに戻す。 */
        fun normalizeCode(raw: String): String =
            raw.filter { it.isLetterOrDigit() }.uppercase()
    }
}
