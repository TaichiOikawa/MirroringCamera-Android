package com.zundataichi.mirroringcamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zundataichi.mirroringcamera.update.UpdateManager
import kotlin.math.roundToInt

/**
 * 新しいバージョンが見つかったときに出すダイアログ。
 *
 * 更新の確認は起動時にも走り、そのとき撮影画面と設定画面のどちらを
 * 開いているかは決まらないので、画面の中ではなく全体に重ねて置く。
 */
@Composable
fun AppUpdateDialog(updateManager: UpdateManager) {
    val release by updateManager.availableRelease.collectAsState()
    val state by updateManager.state.collectAsState()

    val target = release ?: return
    val downloading = state as? UpdateManager.State.Downloading
    val failure = state as? UpdateManager.State.Failed
    // ダウンロード中に閉じられると、進捗の行き先が無くなるので閉じさせない
    val isBusy = downloading != null || state is UpdateManager.State.Installing

    AlertDialog(
        onDismissRequest = { if (!isBusy) updateManager.dismissRelease() },
        title = { Text("アップデートがあります") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // リリースノートが長いと本文がボタンを押し出してしまう
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${updateManager.versionName} → ${target.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (target.releaseNotes.isNotBlank()) {
                    Text(
                        text = target.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (downloading != null) {
                    Text(
                        text = "ダウンロード中 ${downloading.progress.roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { (downloading.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                failure?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { updateManager.startDownloadAndInstall(target) },
                enabled = !isBusy,
            ) {
                Text(if (failure != null) "再試行" else "インストール")
            }
        },
        dismissButton = {
            if (!isBusy) {
                TextButton(onClick = { updateManager.skipRelease(target) }) {
                    Text("このバージョンをスキップ")
                }
            }
        },
    )
}
