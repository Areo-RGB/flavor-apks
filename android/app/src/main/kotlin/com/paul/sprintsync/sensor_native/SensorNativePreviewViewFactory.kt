package com.paul.sprintsync.sensor_native

import android.content.Context
import androidx.camera.view.PreviewView

class SensorNativePreviewViewFactory(
    private val sensorNativeController: SensorNativeController,
) {
    fun createPreviewView(context: Context): PreviewView {
        val previewView = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        sensorNativeController.attachPreviewSurface(previewView)
        return previewView
    }

    fun detachPreviewView(previewView: PreviewView) {
        sensorNativeController.detachPreviewSurface(previewView)
    }
}
