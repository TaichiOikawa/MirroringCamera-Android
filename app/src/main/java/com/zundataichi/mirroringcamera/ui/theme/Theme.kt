package com.zundataichi.mirroringcamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CameraColorScheme = darkColorScheme(
    primary = AccentYellow,
    onPrimary = Color.Black,
    secondary = CameraSurfaceVariant,
    onSecondary = TextWhite,
    background = CameraDark,
    onBackground = TextWhite,
    surface = CameraSurface,
    onSurface = TextWhite,
    surfaceVariant = CameraSurfaceVariant,
    onSurfaceVariant = TextWhiteHalf,
    error = RecordingRed,
    onError = TextWhite,
)

@Composable
fun MirroringCameraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CameraColorScheme,
        typography = Typography,
        content = content
    )
}