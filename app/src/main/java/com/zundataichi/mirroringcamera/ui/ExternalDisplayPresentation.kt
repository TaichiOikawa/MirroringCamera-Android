package com.zundataichi.mirroringcamera.ui

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExternalDisplayManager(private val context: Context) {

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    var onSurfaceAvailable: ((SurfaceHolder) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null

    private var presentation: CameraPresentation? = null
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val display = displayManager.getDisplay(displayId) ?: return
            if (display.flags and Display.FLAG_PRESENTATION != 0) {
                showPresentation(display)
            }
        }

        override fun onDisplayChanged(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {
            hidePresentation()
        }
    }

    fun start() {
        displayManager.registerDisplayListener(displayListener, null)
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isNotEmpty()) {
            showPresentation(displays[0])
        }
    }

    fun stop() {
        displayManager.unregisterDisplayListener(displayListener)
        hidePresentation()
    }

    private fun showPresentation(display: Display) {
        presentation?.dismiss()
        presentation = CameraPresentation(context, display,
            onSurfaceReady = { holder -> onSurfaceAvailable?.invoke(holder) },
            onSurfaceLost = { onSurfaceDestroyed?.invoke() }
        ).also { it.show() }
        _isConnected.value = true
    }

    private fun hidePresentation() {
        presentation?.dismiss()
        presentation = null
        _isConnected.value = false
        onSurfaceDestroyed?.invoke()
    }
}

class CameraPresentation(
    context: Context,
    display: Display,
    private val onSurfaceReady: (SurfaceHolder) -> Unit,
    private val onSurfaceLost: () -> Unit,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceReady(holder)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceLost()
            }
        })
        setContentView(surfaceView)
    }
}
