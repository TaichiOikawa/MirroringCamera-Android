package com.zundataichi.mirroringcamera.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mirroringcamera_settings", Context.MODE_PRIVATE)

    private val _apiBaseURL = MutableStateFlow(prefs.getString(KEY_API_BASE_URL, "") ?: "")
    val apiBaseURL: StateFlow<String> = _apiBaseURL.asStateFlow()

    private val _cameraID = MutableStateFlow(loadOrGenerateCameraID())
    val cameraID: StateFlow<String> = _cameraID.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _previewInterval = MutableStateFlow(prefs.getFloat(KEY_PREVIEW_INTERVAL, 1.0f).toDouble())
    val previewInterval: StateFlow<Double> = _previewInterval.asStateFlow()

    private val _autoUpdateCheckEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_UPDATE_CHECK, true))
    val autoUpdateCheckEnabled: StateFlow<Boolean> = _autoUpdateCheckEnabled.asStateFlow()

    /** 最後に GitHub へ更新を見に行った時刻（epoch ミリ秒）。0 なら未確認。 */
    val lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)

    /** 利用者が「このバージョンは要らない」と指定したタグ。無ければ null。 */
    val skippedUpdateVersion: String?
        get() = prefs.getString(KEY_SKIPPED_UPDATE_VERSION, null)

    fun setApiBaseURL(url: String) {
        val trimmed = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_API_BASE_URL, trimmed).apply()
        _apiBaseURL.value = trimmed
    }

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString(KEY_API_KEY, trimmed).apply()
        _apiKey.value = trimmed
    }

    /**
     * QR ペアリングで受け取った接続情報をまとめて保存する。
     *
     * URL・カメラID・API Key は 3 つで 1 組なので、1 つずつ書いて途中で
     * ちぐはぐな組み合わせが見えないよう、まとめて書き込む。
     */
    fun applyPairing(serverUrl: String, cameraId: String, apiKey: String) {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val normalizedCameraId = cameraId.trim()
        val normalizedKey = apiKey.trim()

        prefs.edit()
            .putString(KEY_API_BASE_URL, normalizedUrl)
            .putString(KEY_CAMERA_ID, normalizedCameraId)
            .putString(KEY_API_KEY, normalizedKey)
            .apply()

        _apiBaseURL.value = normalizedUrl
        _cameraID.value = normalizedCameraId
        _apiKey.value = normalizedKey
    }

    fun setPreviewInterval(interval: Double) {
        prefs.edit().putFloat(KEY_PREVIEW_INTERVAL, interval.toFloat()).apply()
        _previewInterval.value = interval
    }

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, enabled).apply()
        _autoUpdateCheckEnabled.value = enabled
    }

    fun setLastUpdateCheckAt(timestampMillis: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, timestampMillis).apply()
    }

    fun setSkippedUpdateVersion(tagName: String?) {
        prefs.edit().putString(KEY_SKIPPED_UPDATE_VERSION, tagName).apply()
    }

    /**
     * http(s):// URL をそのまま REST 用に正規化して返す。
     * 先頭に http:// がなければ付与し、末尾の / を除去。
     */
    fun getRestUrl(): String {
        val base = _apiBaseURL.value
        if (base.isBlank()) return ""
        val url = if (!base.startsWith("http://") && !base.startsWith("https://")) {
            "http://$base"
        } else {
            base
        }
        return url.trimEnd('/')
    }

    /**
     * REST URL を WebSocket URL に変換し、カメラ接続パスを追加。
     */
    fun getWebSocketUrl(): String {
        val rest = getRestUrl()
        if (rest.isBlank()) return ""
        val ws = rest
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val base = "$ws/ws/camera/${_cameraID.value}"
        val key = _apiKey.value
        return if (key.isNotBlank()) "$base?api_key=$key" else base
    }

    private fun loadOrGenerateCameraID(): String {
        val saved = prefs.getString(KEY_CAMERA_ID, null)
        if (!saved.isNullOrBlank()) return saved

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val shortId = (androidId ?: java.util.UUID.randomUUID().toString().replace("-", ""))
            .take(8)
            .lowercase()
        val cameraId = "android-$shortId"
        prefs.edit().putString(KEY_CAMERA_ID, cameraId).apply()
        return cameraId
    }

    companion object {
        private const val KEY_API_BASE_URL = "apiBaseURL"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_CAMERA_ID = "cameraID"
        private const val KEY_PREVIEW_INTERVAL = "previewInterval"
        private const val KEY_AUTO_UPDATE_CHECK = "autoUpdateCheckEnabled"
        private const val KEY_LAST_UPDATE_CHECK_AT = "lastUpdateCheckAt"
        private const val KEY_SKIPPED_UPDATE_VERSION = "skippedUpdateVersion"
    }
}
