package com.zundataichi.mirroringcamera.pairing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** ペアリングで受け取った、このカメラ専用の接続情報。 */
data class PairingCredentials(
    val serverUrl: String,
    val cameraId: String,
    val displayName: String?,
    val apiKey: String,
)

sealed class PairingResult {
    data class Success(val credentials: PairingCredentials) : PairingResult()
    /** 失敗理由（画面にそのまま出す日本語）。 */
    data class Failure(val message: String) : PairingResult()
}

/**
 * QR のペアリングコードを、このカメラ専用の API Key と引き換える。
 * `POST {server_url}/api/pairing/{code}/claim`（CameraController docs/api.md §0-7-4）。
 */
class PairingClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        private const val TAG = "PairingClient"
        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun claim(
        payload: PairingPayload,
        cameraId: String,
        deviceName: String,
    ): PairingResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("camera_id", cameraId)
            put("device_name", deviceName)
        }.toString()

        val request = Request.Builder()
            .url("${payload.serverUrl}/api/pairing/${payload.code}/claim")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "claim failed: ${response.code} $text")
                    return@withContext PairingResult.Failure(errorMessage(response.code, text))
                }
                val root = json.parseToJsonElement(text).jsonObject
                val apiKey = root["api_key"]?.jsonPrimitive?.contentOrNull
                val claimedCameraId = root["camera_id"]?.jsonPrimitive?.contentOrNull
                if (apiKey.isNullOrBlank() || claimedCameraId.isNullOrBlank()) {
                    return@withContext PairingResult.Failure("サーバーの応答が不正です")
                }
                PairingResult.Success(
                    PairingCredentials(
                        // サーバーが接続先を指定してくることがあるので、返ってきた
                        // 値を優先する。
                        serverUrl = PairingPayload.normalizeServerUrl(
                            root["server_url"]?.jsonPrimitive?.contentOrNull ?: payload.serverUrl
                        ),
                        cameraId = claimedCameraId,
                        displayName = root["display_name"]?.jsonPrimitive?.contentOrNull,
                        apiKey = apiKey,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "claim error: ${e.message}")
            PairingResult.Failure("サーバーに接続できません（${payload.serverUrl}）")
        }
    }

    /** サーバーの HTTP ステータス（docs/api.md §0-7-4）を画面向けの文言にする。 */
    private fun errorMessage(status: Int, body: String): String = when (status) {
        400 -> "カメラIDが不正です"
        404 -> "このQRコードは無効です。管理画面で再発行してください"
        409 -> "このQRコードは既に使用済みです。再発行してください"
        410 -> "QRコードの有効期限が切れています。再発行してください"
        429 -> "試行回数が多すぎます。しばらく待ってからお試しください"
        else -> detail(body) ?: "接続に失敗しました（HTTP $status）"
    }

    private fun detail(body: String): String? = try {
        json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }
}
