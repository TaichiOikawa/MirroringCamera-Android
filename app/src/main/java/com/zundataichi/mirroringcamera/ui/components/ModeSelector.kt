package com.zundataichi.mirroringcamera.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zundataichi.mirroringcamera.manager.CaptureMode
import com.zundataichi.mirroringcamera.ui.theme.AccentYellow
import com.zundataichi.mirroringcamera.ui.theme.TextWhiteHalf

@Composable
fun ModeSelector(
    currentMode: CaptureMode,
    isRecording: Boolean,
    isTimeLapseRunning: Boolean,
    onModeSelected: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        CaptureMode.entries.forEach { mode ->
            val isSelected = mode == currentMode
            val isDisabled = when {
                isRecording && mode != CaptureMode.VIDEO -> true
                isTimeLapseRunning && mode != CaptureMode.TIMELAPSE -> true
                else -> false
            }

            Text(
                text = when (mode) {
                    CaptureMode.PHOTO -> "写真"
                    CaptureMode.VIDEO -> "ビデオ"
                    CaptureMode.TIMELAPSE -> "タイムラプス"
                },
                color = when {
                    isDisabled -> TextWhiteHalf.copy(alpha = 0.3f)
                    isSelected -> AccentYellow
                    else -> TextWhiteHalf
                },
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier.clickable(enabled = !isDisabled) {
                    onModeSelected(mode)
                }
            )
        }
    }
}
