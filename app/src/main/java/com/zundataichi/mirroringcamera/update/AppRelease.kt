package com.zundataichi.mirroringcamera.update

/** GitHub Releases から取得した、配布中のバージョン1件分。 */
data class AppRelease(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
)

/**
 * `v1.2.3` のようなバージョン文字列を、数値の並びとして比較する。
 * a > b なら正、a < b なら負、同じなら 0。
 *
 * 文字列のまま比べると "1.10" < "1.9" になってしまうため、必ずここを通す。
 */
fun compareVersions(a: String, b: String): Int {
    val left = versionNumbers(a)
    val right = versionNumbers(b)
    repeat(maxOf(left.size, right.size)) { index ->
        // 桁数が違う場合（1.2 と 1.2.0）は、無い部分を 0 とみなして同じ扱いにする
        val diff = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/** 現在動いているバージョンより新しければ true。 */
fun AppRelease.isNewerThan(currentVersionName: String): Boolean =
    compareVersions(versionName, currentVersionName) > 0

/**
 * "v1.2.3-beta.1" → [1, 2, 3]。
 * 先頭の v と、プレリリース以降（- より後ろ）は落とす。
 */
private fun versionNumbers(value: String): List<Int> =
    value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .split('.')
        .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
