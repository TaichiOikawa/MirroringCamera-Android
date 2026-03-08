package com.zundataichi.mirroringcamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zundataichi.mirroringcamera.ui.theme.ConnectionGreen
import com.zundataichi.mirroringcamera.ui.theme.ConnectionOrange
import com.zundataichi.mirroringcamera.ui.theme.ConnectionRed

enum class ConnectionStatus { CONNECTED, CONNECTING, DISCONNECTED }

@Composable
fun ConnectionIndicator(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> ConnectionGreen
        ConnectionStatus.CONNECTING -> ConnectionOrange
        ConnectionStatus.DISCONNECTED -> ConnectionRed.copy(alpha = 0.6f)
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}
