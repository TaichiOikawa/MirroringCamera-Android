package com.zundataichi.mirroringcamera

import android.app.Application
import com.zundataichi.mirroringcamera.data.SettingsStore
import com.zundataichi.mirroringcamera.manager.WebSocketManager

class MirroringCameraApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var webSocketManager: WebSocketManager
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        webSocketManager = WebSocketManager(this, settingsStore)
    }
}
