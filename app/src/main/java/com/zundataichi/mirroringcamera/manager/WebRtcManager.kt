package com.zundataichi.mirroringcamera.manager

import android.content.Context
import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Owns the WebRTC offerer side of the camera endpoint.
 *
 * The camera is the offerer (see CameraController/docs/api.md §2-4): on `rtc_subscribe` it
 * creates a [PeerConnection] for the `(control_id, session_id)` pair, attaches the single shared
 * sendonly H.264 video track, and sends an `rtc_offer`. Every viewer gets its own PeerConnection
 * but they all share one [VideoSource]/[VideoTrack]/encoder to keep device heat down.
 *
 * App-scoped: signaling arrives on the app-scoped WebSocket, while camera frames are pushed in
 * from the activity-scoped [CameraManager] via [pushFrame].
 */
class WebRtcManager(context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_TRACK_ID = "cam-video"
        private const val STREAM_ID = "cam-stream"
        private const val STUN_URL = "stun:stun.l.google.com:19302"

        // Grace period before declaring WebRTC unusable and switching to the
        // JPEG fallback. Gives ICE/DTLS time to connect, and rides out brief
        // DISCONNECTED blips that usually recover to CONNECTED.
        private const val FALLBACK_GRACE_MS = 6000L
    }

    private data class Session(
        val controlId: String,
        val sessionId: String,
        val pc: PeerConnection,
    )

    // Local signaling callbacks (wired to WebSocketManager senders).
    var onLocalOffer: ((controlId: String, sessionId: String, sdp: String) -> Unit)? = null
    var onLocalIce: ((controlId: String, sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit)? = null
    var onLocalClose: ((controlId: String, sessionId: String) -> Unit)? = null

    /**
     * Fired (on the main thread) when the JPEG fallback should start/stop.
     * `true` once there are viewers but none reach a live WebRTC connection
     * within [FALLBACK_GRACE_MS]; `false` again as soon as any viewer connects
     * (or all viewers leave). See CameraController/docs/api.md `preview`.
     */
    var onFallbackActiveChanged: ((Boolean) -> Unit)? = null

    private val lock = Any()
    private val sessions = HashMap<String, Session>() // session_id -> Session
    private val connectedSessions = HashSet<String>() // session_ids with a live PC

    private val mainHandler = Handler(Looper.getMainLooper())
    private var graceScheduled = false
    private var fallbackActive = false

    // Teardown runs on its own thread: disposing a PeerConnection from its own observer
    // callback thread can deadlock.
    private val teardownExecutor = Executors.newSingleThreadExecutor()

    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val videoSource: VideoSource
    private val videoTrack: VideoTrack

    private val iceServers = listOf(
        PeerConnection.IceServer.builder(STUN_URL).createIceServer()
    )

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ false, // H.264 Constrained Baseline (api.md §2-4)
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Single shared source/track for all viewers. isScreencast = false.
        videoSource = factory.createVideoSource(false)
        videoSource.capturerObserver.onCapturerStarted(true)
        videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource).apply {
            setEnabled(true)
        }
    }

    // --- Inbound signaling (from server, relayed by WebSocketManager) ---

    /** A viewer wants to watch: build a PeerConnection and send an offer. */
    fun onSubscribe(controlId: String, sessionId: String) {
        synchronized(lock) {
            if (sessions.containsKey(sessionId)) return

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val pc = factory.createPeerConnection(rtcConfig, makeObserver(controlId, sessionId))
            if (pc == null) {
                Log.e(TAG, "createPeerConnection returned null for session=$sessionId")
                return
            }
            pc.addTransceiver(
                videoTrack,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    listOf(STREAM_ID),
                ),
            )
            val session = Session(controlId, sessionId, pc)
            sessions[sessionId] = session
            createAndSendOffer(session)
            updateFallback()
        }
    }

    fun onRemoteAnswer(sessionId: String, sdp: String) {
        val session = synchronized(lock) { sessions[sessionId] } ?: return
        session.pc.setRemoteDescription(
            loggingSdpObserver("setRemoteDescription(answer)"),
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    fun onRemoteIce(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val session = synchronized(lock) { sessions[sessionId] } ?: return
        session.pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    /** A single viewer stopped watching. */
    fun onUnsubscribe(sessionId: String) {
        closeSession(sessionId, notify = false)
    }

    /** A viewer disconnected entirely: free every PeerConnection it owned. */
    fun onControlGone(controlId: String) {
        val gone = synchronized(lock) {
            val matching = sessions.values.filter { it.controlId == controlId }
            matching.forEach {
                sessions.remove(it.sessionId)
                connectedSessions.remove(it.sessionId)
            }
            updateFallback()
            matching
        }
        gone.forEach { disposeAsync(it.pc) }
    }

    /** Signaling socket dropped: stale PeerConnections are useless. */
    fun closeAll() {
        val all = synchronized(lock) {
            val copy = sessions.values.toList()
            sessions.clear()
            connectedSessions.clear()
            updateFallback()
            copy
        }
        all.forEach { disposeAsync(it.pc) }
    }

    // --- Frame ingestion (from CameraManager.onFrameForWebRtc) ---

    /**
     * Convert a CameraX YUV_420_888 [ImageProxy] to a WebRTC [VideoFrame] (I420) and push it to the
     * shared source. Must copy synchronously — the caller closes the ImageProxy right after.
     */
    fun pushFrame(image: ImageProxy) {
        // Only encode while at least one viewer is actually connected. During
        // the JPEG fallback (no live PC) this skips the H.264 encoder entirely,
        // which keeps device heat down.
        val hasLiveViewer = synchronized(lock) { connectedSessions.isNotEmpty() }
        if (!hasLiveViewer) return
        if (image.format != ImageFormat.YUV_420_888) return
        try {
            val buffer = imageProxyToI420(image)
            // Rotation is carried as metadata so the encoder/receiver rotates, not us.
            val frame = VideoFrame(buffer, image.imageInfo.rotationDegrees, System.nanoTime())
            videoSource.capturerObserver.onFrameCaptured(frame)
            frame.release()
        } catch (e: Exception) {
            Log.e(TAG, "pushFrame failed", e)
        }
    }

    fun dispose() {
        closeAll()
        synchronized(lock) { cancelGrace() }
        teardownExecutor.execute {
            try {
                videoTrack.dispose()
                videoSource.dispose()
                factory.dispose()
                eglBase.release()
            } catch (e: Exception) {
                Log.w(TAG, "dispose error: ${e.message}")
            }
        }
        teardownExecutor.shutdown()
    }

    // --- Internals ---

    private fun createAndSendOffer(session: Session) {
        session.pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                session.pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        onLocalOffer?.invoke(session.controlId, session.sessionId, desc.description)
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription(offer) failed: $error")
                    }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed: $error")
            }

            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun closeSession(sessionId: String, notify: Boolean) {
        val session = synchronized(lock) {
            val removed = sessions.remove(sessionId) ?: return@synchronized null
            connectedSessions.remove(sessionId)
            updateFallback()
            removed
        } ?: return
        if (notify) onLocalClose?.invoke(session.controlId, session.sessionId)
        disposeAsync(session.pc)
    }

    // --- Fallback (JPEG) state machine ---

    /** Recompute whether WebRTC is usable and toggle the JPEG fallback. Caller holds [lock]. */
    private fun updateFallback() {
        val shouldFallback = sessions.isNotEmpty() && connectedSessions.isEmpty()
        if (!shouldFallback) {
            cancelGrace()
            setFallbackActive(false)
        } else if (!fallbackActive) {
            // Viewers exist but none are connected — wait out the grace window
            // before declaring WebRTC dead and switching to JPEG.
            scheduleGraceCheck()
        }
    }

    private fun scheduleGraceCheck() {
        if (graceScheduled) return
        graceScheduled = true
        mainHandler.postDelayed(graceRunnable, FALLBACK_GRACE_MS)
    }

    private fun cancelGrace() {
        if (!graceScheduled) return
        graceScheduled = false
        mainHandler.removeCallbacks(graceRunnable)
    }

    private val graceRunnable = Runnable {
        synchronized(lock) {
            graceScheduled = false
            if (sessions.isNotEmpty() && connectedSessions.isEmpty()) {
                setFallbackActive(true)
            }
        }
    }

    private fun setFallbackActive(active: Boolean) {
        if (fallbackActive == active) return
        fallbackActive = active
        mainHandler.post { onFallbackActiveChanged?.invoke(active) }
    }

    private fun disposeAsync(pc: PeerConnection) {
        teardownExecutor.execute {
            try {
                pc.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "pc dispose error: ${e.message}")
            }
        }
    }

    private fun makeObserver(controlId: String, sessionId: String): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onLocalIce?.invoke(
                    controlId,
                    sessionId,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex,
                    candidate.sdp,
                )
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                // Note: we deliberately keep the session on FAILED/CLOSED (do not
                // tear it down). The viewer still wants video, so the subscription
                // stays alive and the JPEG fallback takes over until WebRTC
                // recovers or the viewer explicitly unsubscribes.
                synchronized(lock) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            connectedSessions.add(sessionId)
                            updateFallback()
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            connectedSessions.remove(sessionId)
                            updateFallback()
                        }
                        else -> {}
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
        }

    private fun loggingSdpObserver(tag: String): SdpObserver = object : SdpObserver {
        override fun onSetSuccess() {}
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$tag failed: $error")
        }
    }

    private fun imageProxyToI420(image: ImageProxy): JavaI420Buffer {
        val width = image.width
        val height = image.height
        val i420 = JavaI420Buffer.allocate(width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, i420.dataY, i420.strideY, width, height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, i420.dataU, i420.strideU, chromaWidth, chromaHeight)
        copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, i420.dataV, i420.strideV, chromaWidth, chromaHeight)
        return i420
    }

    private fun copyPlane(
        src: ByteBuffer,
        srcRowStride: Int,
        srcPixelStride: Int,
        dst: ByteBuffer,
        dstStride: Int,
        width: Int,
        height: Int,
    ) {
        val srcDup = src.duplicate()
        val rowData = ByteArray(width)
        for (row in 0 until height) {
            val srcRowStart = row * srcRowStride
            if (srcPixelStride == 1) {
                srcDup.position(srcRowStart)
                srcDup.get(rowData, 0, width)
            } else {
                var srcIndex = srcRowStart
                for (col in 0 until width) {
                    rowData[col] = srcDup.get(srcIndex)
                    srcIndex += srcPixelStride
                }
            }
            dst.position(row * dstStride)
            dst.put(rowData, 0, width)
        }
    }
}
