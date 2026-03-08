package com.zundataichi.mirroringcamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zundataichi.mirroringcamera.manager.CaptureMode
import com.zundataichi.mirroringcamera.ui.theme.RecordingRed
import com.zundataichi.mirroringcamera.ui.theme.TimeLapseOrange

@Composable
fun ShutterButton(
    captureMode: CaptureMode,
    isRecording: Boolean,
    isTimeLapseRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when (captureMode) {
            CaptureMode.PHOTO -> {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            CaptureMode.VIDEO -> {
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(RecordingRed)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(RecordingRed)
                    )
                }
            }
            CaptureMode.TIMELAPSE -> {
                if (isTimeLapseRunning) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(TimeLapseOrange)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(TimeLapseOrange)
                    )
                }
            }
        }
    }
}
