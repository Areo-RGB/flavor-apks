package com.paul.sprintsync.sensor_native

import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class SensorNativePreviewViewFactory(
    private val sensorNativeController: SensorNativeController,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        return SensorNativePreviewPlatformView(
            context = context,
            sensorNativeController = sensorNativeController,
        )
    }
}
