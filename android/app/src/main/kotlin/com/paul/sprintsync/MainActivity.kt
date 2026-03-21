package com.paul.sprintsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.paul.sprintsync.sensor_native.SensorNativeController
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.nio.charset.StandardCharsets

class MainActivity : FlutterActivity(), ActivityCompat.OnRequestPermissionsResultCallback {
    companion object {
        private const val METHOD_CHANNEL_NAME = "com.paul.sprintsync/nearby_methods"
        private const val EVENT_CHANNEL_NAME = "com.paul.sprintsync/nearby_events"
        private const val PERMISSIONS_REQUEST_CODE = 7301
        private val STRATEGY = Strategy.P2P_STAR
    }

    private enum class NearbyRole {
        NONE,
        HOST,
        CLIENT,
    }

    private val connectedEndpointIds = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var sensorNativeController: SensorNativeController

    private var eventSink: EventChannel.EventSink? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    private var activeRole: NearbyRole = NearbyRole.NONE
    private var pendingEndpointId: String? = null
    private var requestedEndpointId: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        connectionsClient = Nearby.getConnectionsClient(this)
        sensorNativeController = SensorNativeController(this)
        sensorNativeController.configure(flutterEngine.dartExecutor.binaryMessenger)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL_NAME,
        ).setMethodCallHandler(::handleMethodCall)

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL_NAME,
        ).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    eventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            },
        )
    }

    override fun onPause() {
        if (::sensorNativeController.isInitialized) {
            sensorNativeController.onHostPaused()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::sensorNativeController.isInitialized) {
            sensorNativeController.dispose()
        }
        super.onDestroy()
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestPermissions" -> requestPermissions(result)
            "startHosting" -> {
                val serviceId = stringArg(call, "serviceId", result) ?: return
                val endpointName = stringArg(call, "endpointName", result) ?: return
                startHosting(serviceId, endpointName, result)
            }

            "stopHosting" -> {
                stopHosting()
                result.success(null)
            }

            "startDiscovery" -> {
                val serviceId = stringArg(call, "serviceId", result) ?: return
                startDiscovery(serviceId, result)
            }

            "stopDiscovery" -> {
                stopDiscovery()
                result.success(null)
            }

            "requestConnection" -> {
                val endpointId = stringArg(call, "endpointId", result) ?: return
                val endpointName = stringArg(call, "endpointName", result) ?: return
                requestConnection(endpointId, endpointName, result)
            }

            "sendBytes" -> {
                val endpointId = stringArg(call, "endpointId", result) ?: return
                val messageJson = stringArg(call, "messageJson", result) ?: return
                sendBytes(endpointId, messageJson, result)
            }

            "disconnect" -> {
                val endpointId = stringArg(call, "endpointId", result) ?: return
                disconnect(endpointId)
                result.success(null)
            }

            "stopAll" -> {
                stopAll()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun stringArg(
        call: MethodCall,
        key: String,
        result: MethodChannel.Result,
    ): String? {
        val value = call.argument<String>(key)
        if (value.isNullOrBlank()) {
            result.error("bad_args", "Missing required argument '$key'.", null)
            return null
        }
        return value
    }

    private fun requestPermissions(result: MethodChannel.Result) {
        if (pendingPermissionResult != null) {
            result.error("permissions_in_flight", "A permission request is already running.", null)
            return
        }
        val denied = deniedPermissions()
        if (denied.isEmpty()) {
            val payload = mapOf(
                "granted" to true,
                "denied" to emptyList<String>(),
            )
            result.success(payload)
            emitEvent(
                mapOf(
                    "type" to "permission_status",
                    "granted" to true,
                    "denied" to emptyList<String>(),
                ),
            )
            return
        }

        pendingPermissionResult = result
        ActivityCompat.requestPermissions(
            this,
            denied.toTypedArray(),
            PERMISSIONS_REQUEST_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return
        }

        val callback = pendingPermissionResult ?: return
        pendingPermissionResult = null

        val denied = deniedPermissions()
        val granted = denied.isEmpty()
        callback.success(
            mapOf(
                "granted" to granted,
                "denied" to denied,
            ),
        )

        emitEvent(
            mapOf(
                "type" to "permission_status",
                "granted" to granted,
                "denied" to denied,
            ),
        )
    }

    private fun startHosting(
        serviceId: String,
        endpointName: String,
        result: MethodChannel.Result,
    ) {
        normalizeForRole(NearbyRole.HOST)
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()
        connectionsClient
            .startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { result.success(null) }
            .addOnFailureListener { error ->
                clearTransientState()
                emitError("startHosting failed: ${error.localizedMessage ?: "unknown"}")
                result.error("start_hosting_failed", error.localizedMessage, null)
            }
    }

    private fun stopHosting() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = NearbyRole.NONE
    }

    private fun startDiscovery(
        serviceId: String,
        result: MethodChannel.Result,
    ) {
        normalizeForRole(NearbyRole.CLIENT)
        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()
        connectionsClient
            .startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { result.success(null) }
            .addOnFailureListener { error ->
                clearTransientState()
                emitError("startDiscovery failed: ${error.localizedMessage ?: "unknown"}")
                result.error("start_discovery_failed", error.localizedMessage, null)
            }
    }

    private fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = NearbyRole.NONE
    }

    private fun requestConnection(
        endpointId: String,
        endpointName: String,
        result: MethodChannel.Result,
    ) {
        if (activeRole != NearbyRole.CLIENT) {
            val message = "requestConnection ignored: not in client mode."
            emitError(message)
            result.error("invalid_role", message, null)
            return
        }
        if (connectedEndpointIds.isNotEmpty() && !connectedEndpointIds.contains(endpointId)) {
            val message = "requestConnection ignored: already connected to another endpoint."
            emitError(message)
            result.error("already_connected", message, null)
            return
        }
        if (pendingEndpointId != null && pendingEndpointId != endpointId) {
            val message = "requestConnection ignored: another connection is pending."
            emitError(message)
            result.error("pending_connection", message, null)
            return
        }
        if (requestedEndpointId != null && requestedEndpointId != endpointId) {
            val message = "requestConnection ignored: request already in flight."
            emitError(message)
            result.error("request_in_flight", message, null)
            return
        }

        requestedEndpointId = endpointId
        connectionsClient
            .requestConnection(endpointName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { result.success(null) }
            .addOnFailureListener { error ->
                if (requestedEndpointId == endpointId) {
                    requestedEndpointId = null
                }
                emitError("requestConnection failed: ${error.localizedMessage ?: "unknown"}")
                result.error("request_connection_failed", error.localizedMessage, null)
            }
    }

    private fun sendBytes(
        endpointId: String,
        messageJson: String,
        result: MethodChannel.Result,
    ) {
        if (!connectedEndpointIds.contains(endpointId)) {
            val message = "sendBytes ignored: endpoint not connected ($endpointId)."
            emitError(message)
            result.error("endpoint_not_connected", message, null)
            return
        }
        val payload = Payload.fromBytes(messageJson.toByteArray(StandardCharsets.UTF_8))
        connectionsClient
            .sendPayload(endpointId, payload)
            .addOnSuccessListener { result.success(null) }
            .addOnFailureListener { error ->
                emitError("sendBytes failed: ${error.localizedMessage ?: "unknown"}")
                result.error("send_payload_failed", error.localizedMessage, null)
            }
    }

    private fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        clearEndpointState(endpointId)
        emitEvent(
            mapOf(
                "type" to "endpoint_disconnected",
                "endpointId" to endpointId,
            ),
        )
    }

    private fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = NearbyRole.NONE
    }

    private fun deniedPermissions(): List<String> {
        return requiredPermissions()
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        return permissions.distinct()
    }

    private fun normalizeForRole(role: NearbyRole) {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = role
    }

    private fun clearTransientState() {
        pendingEndpointId = null
        requestedEndpointId = null
        connectedEndpointIds.clear()
    }

    private fun clearEndpointState(endpointId: String) {
        if (pendingEndpointId == endpointId) {
            pendingEndpointId = null
        }
        if (requestedEndpointId == endpointId) {
            requestedEndpointId = null
        }
        connectedEndpointIds.remove(endpointId)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (activeRole != NearbyRole.CLIENT) {
                return
            }
            emitEvent(
                mapOf(
                    "type" to "endpoint_found",
                    "endpointId" to endpointId,
                    "endpointName" to info.endpointName,
                    "serviceId" to info.serviceId,
                ),
            )
        }

        override fun onEndpointLost(endpointId: String) {
            clearEndpointState(endpointId)
            emitEvent(
                mapOf(
                    "type" to "endpoint_lost",
                    "endpointId" to endpointId,
                ),
            )
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val hasPendingDifferent = pendingEndpointId != null && pendingEndpointId != endpointId
            val clientBusy = activeRole == NearbyRole.CLIENT &&
                connectedEndpointIds.isNotEmpty() &&
                !connectedEndpointIds.contains(endpointId)

            if (hasPendingDifferent || clientBusy) {
                connectionsClient.rejectConnection(endpointId)
                emitEvent(
                    mapOf(
                        "type" to "connection_result",
                        "endpointId" to endpointId,
                        "connected" to false,
                        "statusCode" to ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
                        "statusMessage" to "Connection rejected: competing connection state.",
                    ),
                )
                return
            }

            pendingEndpointId = endpointId
            if (activeRole == NearbyRole.CLIENT) {
                requestedEndpointId = endpointId
            }

            // Auto-accept for local open sessions in v1.
            connectionsClient
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    clearEndpointState(endpointId)
                    emitEvent(
                        mapOf(
                            "type" to "connection_result",
                            "endpointId" to endpointId,
                            "connected" to false,
                            "statusCode" to ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
                            "statusMessage" to (error.localizedMessage
                                ?: "acceptConnection failed"),
                        ),
                    )
                    emitError("acceptConnection failed: ${error.localizedMessage ?: "unknown"}")
                }
        }

        override fun onConnectionResult(
            endpointId: String,
            resolution: ConnectionResolution,
        ) {
            val status = resolution.status
            val isConnected = status.statusCode == ConnectionsStatusCodes.STATUS_OK
            if (isConnected) {
                connectedEndpointIds.add(endpointId)
                clearEndpointState(endpointId)
                connectedEndpointIds.add(endpointId)
                if (activeRole == NearbyRole.CLIENT) {
                    connectionsClient.stopDiscovery()
                }
            } else {
                clearEndpointState(endpointId)
            }
            emitEvent(
                mapOf(
                    "type" to "connection_result",
                    "endpointId" to endpointId,
                    "connected" to isConnected,
                    "statusCode" to status.statusCode,
                    "statusMessage" to status.statusMessage,
                ),
            )
        }

        override fun onDisconnected(endpointId: String) {
            clearEndpointState(endpointId)
            emitEvent(
                mapOf(
                    "type" to "endpoint_disconnected",
                    "endpointId" to endpointId,
                ),
            )
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val message = String(bytes, StandardCharsets.UTF_8)
            emitEvent(
                mapOf(
                    "type" to "payload_received",
                    "endpointId" to endpointId,
                    "message" to message,
                ),
            )
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Byte payloads are handled in onPayloadReceived; transfer progress is not needed for v1.
        }
    }

    private fun emitError(message: String) {
        emitEvent(
            mapOf(
                "type" to "error",
                "message" to message,
            ),
        )
    }

    private fun emitEvent(event: Map<String, Any?>) {
        val sink = eventSink ?: return
        mainHandler.post { sink.success(event) }
    }
}
