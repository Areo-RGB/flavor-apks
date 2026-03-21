package com.paul.sprintsync.sensor_native

import android.content.Context
import android.view.View
import androidx.camera.view.PreviewView
import io.flutter.plugin.platform.PlatformView

class SensorNativePreviewPlatformView(
    context: Context,
    private val sensorNativeController: SensorNativeController,
) : PlatformView {
    private val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
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
