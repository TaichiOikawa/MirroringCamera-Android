package com.zundataichi.mirroringcamera.util

import android.view.KeyEvent

/**
 * 音量ボタンをシャッターとして使用するためのヘルパー。
 * Activity.onKeyDown() から呼び出す。
 */
class VolumeButtonShutter {

    var onShutterPress: (() -> Unit)? = null

    /**
     * @return true if the event was consumed (volume button press)
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onShutterPress?.invoke()
            return true
        }
        return false
    }
}
