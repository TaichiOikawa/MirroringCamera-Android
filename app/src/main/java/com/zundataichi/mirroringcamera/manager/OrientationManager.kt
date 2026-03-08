package com.zundataichi.mirroringcamera.manager

import android.app.Activity
import android.content.pm.ActivityInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OrientationManager {

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun toggleLock(activity: Activity) {
        if (_isLocked.value) {
            unlock(activity)
        } else {
            lock(activity)
        }
    }

    private fun lock(activity: Activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        _isLocked.value = true
    }

    private fun unlock(activity: Activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        _isLocked.value = false
    }
}
