package com.zundataichi.mirroringcamera

import android.app.Application
import com.zundataichi.mirroringcamera.data.SettingsStore
import com.zundataichi.mirroringcamera.manager.WebRtcManager
import com.zundataichi.mirroringcamera.manager.WebSocketManager
import com.zundataichi.mirroringcamera.update.UpdateManager

class MirroringCameraApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var webSocketManager: WebSocketManager
        private set

    lateinit var webRtcManager: WebRtcManager
        private set

    lateinit var updateManager: UpdateManager
        private set

    /**
     * True while WebRTC cannot connect and the JPEG fallback should run. Read by
     * the activity-scoped CameraManager to decide whether to send `preview` frames.
     */
    @Volatile
    var webRtcFallbackActive = false
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        webSocketManager = WebSocketManager(this, settingsStore)
        webRtcManager = WebRtcManager(this)
        updateManager = UpdateManager(this, settingsStore)

        wireSignaling()

        // 起動時にアプリ更新を確認する（24時間に1回まで／失敗しても起動は妨げない）
        updateManager.checkOnStartup()
    }

    /** Relay WebRTC signaling between the WebSocket and the peer connections. */
    private fun wireSignaling() {
        webSocketManager.onRtcSubscribe = { controlId, sessionId ->
            webRtcManager.onSubscribe(controlId, sessionId)
        }
        webSocketManager.onRtcAnswer = { sessionId, sdp ->
            webRtcManager.onRemoteAnswer(sessionId, sdp)
        }
        webSocketManager.onRtcIce = { sessionId, sdpMid, sdpMLineIndex, candidate ->
            webRtcManager.onRemoteIce(sessionId, sdpMid, sdpMLineIndex, candidate)
        }
        webSocketManager.onRtcUnsubscribe = { sessionId ->
            webRtcManager.onUnsubscribe(sessionId)
        }
        webSocketManager.onRtcControlGone = { controlId ->
            webRtcManager.onControlGone(controlId)
        }
        webSocketManager.onSignalingClosed = {
            webRtcManager.closeAll()
        }

        webRtcManager.onLocalOffer = { controlId, sessionId, sdp ->
            webSocketManager.sendRtcOffer(controlId, sessionId, sdp)
        }
        webRtcManager.onLocalIce = { controlId, sessionId, sdpMid, sdpMLineIndex, candidate ->
            webSocketManager.sendRtcIce(controlId, sessionId, sdpMid, sdpMLineIndex, candidate)
        }
        webRtcManager.onLocalClose = { controlId, sessionId ->
            webSocketManager.sendRtcClose(controlId, sessionId)
        }
        webRtcManager.onFallbackActiveChanged = { active ->
            webRtcFallbackActive = active
        }
    }
}
