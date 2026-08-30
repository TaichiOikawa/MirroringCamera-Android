package com.zundataichi.mirroringcamera.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases API から最新リリースを取得する。
 *
 * WebSocket と同じ OkHttp を使うが、更新確認は待ち時間の性質が違う
 * （読み取りは無期限にせず必ず打ち切る）ので、クライアントは別に持つ。
 */
class GitHubReleaseClient(private val repository: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 最新リリース。まだ1件も公開されていなければ null。 */
    suspend fun fetchLatestRelease(): Result<AppRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repository/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    // リリースが1件も無いリポジトリは 404 を返す。エラーではなく「更新なし」
                    response.code == 404 -> null
                    !response.isSuccessful ->
                        throw IllegalStateException("GitHub API がエラーを返しました (HTTP ${response.code})")

                    else -> parseRelease(response.body?.string().orEmpty())
                }
            }
        }.onFailure { Log.w(TAG, "最新リリースの取得に失敗", it) }
    }

    /** APK が添付されていないリリース（ビルド失敗など）は、更新先にできないので null にする。 */
    private fun parseRelease(body: String): AppRelease? {
        if (body.isBlank()) return null
        val release: JsonObject = json.parseToJsonElement(body).jsonObject

        if (release["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        val tagName = release["tag_name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: return null

        val apkAsset = release["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { asset ->
                asset["name"]?.jsonPrimitive?.contentOrNull
                    ?.endsWith(".apk", ignoreCase = true) == true
            }
        val apkUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: return null

        return AppRelease(
            tagName = tagName,
            versionName = tagName.removePrefix("v").removePrefix("V"),
            releaseNotes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            apkUrl = apkUrl,
            apkSizeBytes = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    private companion object {
        const val TAG = "GitHubReleaseClient"
        const val TIMEOUT_SEC = 15L
        const val USER_AGENT = "MirroringCamera-Android"
    }
}
