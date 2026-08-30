package com.zundataichi.mirroringcamera.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** リリース APK のダウンロードと、システムのインストーラー起動を受け持つ。 */
class ApkInstaller(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** APK の置き場。キャッシュなので、空き容量が足りなくなれば OS に消されてよい。 */
    private val updateDir: File
        get() = File(context.cacheDir, UPDATE_DIR_NAME).apply { mkdirs() }

    /**
     * 「提供元不明のアプリ」のインストールが、このアプリに許可されているか。
     *
     * アプリ単位の許可は Android 8 からで、それ以前は端末全体の設定しか無く
     * 事前に確かめる手段が無い。確認できないものを不許可扱いにすると
     * インストールに進めなくなるため、そのままインストーラーへ渡す。
     */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 許可されていないときに、設定画面へ誘導する。 */
    fun requestInstallPermission(): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * APK をキャッシュへ取得する。[onProgress] には 0f〜100f が渡る。
     * 進捗は 1% 以上動いたときだけ通知して、UI の再描画を抑える。
     */
    suspend fun download(
        release: AppRelease,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // 中断した APK が残っていることがあるので、毎回きれいにしてから始める
            clearDownloadedApks()
            val target = File(updateDir, "mirroringcamera-${release.versionName}.apk")

            val request = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("APK のダウンロードに失敗しました (HTTP ${response.code})")
                }
                val body = response.body ?: throw IllegalStateException("APK の中身が空でした")
                // Content-Length が無い配信もあるので、その場合は API が返したサイズを使う
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSizeBytes

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var reported = 0f
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total <= 0) continue
                            val percent = (downloaded * 100f / total).coerceIn(0f, 100f)
                            if (percent - reported >= 1f) {
                                reported = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            onProgress(100f)
            target
        }.onFailure { error ->
            Log.e(TAG, "APK のダウンロードに失敗", error)
            clearDownloadedApks()
        }
    }

    /** システムのインストーラーを開く。 */
    fun install(apk: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrElse { error ->
            Log.e(TAG, "APK の URI を作れなかった", error)
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun clearDownloadedApks() {
        updateDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("apk", ignoreCase = true)) file.delete()
        }
    }

    private companion object {
        const val TAG = "ApkInstaller"
        const val UPDATE_DIR_NAME = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val CONNECT_TIMEOUT_SEC = 15L
        const val READ_TIMEOUT_SEC = 60L
        const val USER_AGENT = "MirroringCamera-Android"
    }
}
