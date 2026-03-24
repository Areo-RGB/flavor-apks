package com.paul.sprintsync.sensor_native

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import io.flutter.plugin.platform.PlatformView

class SensorNativePreviewPlatformView(
    context: Context,
    private val sensorNativeController: SensorNativeController,
) : PlatformView {
    private val previewView: TextureView = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                sensorNativeController.attachPreviewSurface(this@apply)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                sensorNativeController.attachPreviewSurface(this@apply)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                sensorNativeController.detachPreviewSurface(this@apply)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // no-op
            }
        }
    }

    init {
        sensorNativeController.attachPreviewSurface(previewView)
    }

    override fun getView(): View {
        return previewView
    }

    override fun dispose() {
        sensorNativeController.detachPreviewSurface(previewView)
    }
}
