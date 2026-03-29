package com.paul.sprintsync.core.services

import android.content.Context
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.tasks.Tasks
import io.mockk.capture
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NearbyConnectionsManagerTest {
    @Test
    fun `native clock sync host config survives host startup normalization`() {
        val context = mockk<Context>(relaxed = true)
        val connectionsClient = mockk<ConnectionsClient>(relaxed = true)
        every {
            connectionsClient.startAdvertising(
                any<String>(),
                any<String>(),
                any<ConnectionLifecycleCallback>(),
                any<AdvertisingOptions>(),
            )
        } returns Tasks.forResult<Void>(null)

        val manager = NearbyConnectionsManager(
            context = context,
            nowNativeClockSyncElapsedNanos = { 1L },
            connectionsClient = connectionsClient,
        )

        manager.configureNativeClockSyncHost(enabled = true, requireSensorDomainClock = false)

        manager.startHosting(
            serviceId = "svc",
            endpointName = "host",
            strategy = NearbyTransportStrategy.POINT_TO_POINT,
        ) { _ -> }

        val (enabled, requireSensorDomain) = manager.nativeClockSyncHostConfigForTest()
        assertTrue(enabled)
        assertEquals(false, requireSensorDomain)
    }

    @Test
    fun `point to point host rejects second incoming endpoint`() {
        val context = mockk<Context>(relaxed = true)
        val connectionsClient = mockk<ConnectionsClient>(relaxed = true)
        val lifecycleCallback = slot<ConnectionLifecycleCallback>()
        every {
            connectionsClient.startAdvertising(
                any<String>(),
                any<String>(),
                capture(lifecycleCallback),
                any<AdvertisingOptions>(),
            )
        } returns Tasks.forResult<Void>(null)
        every { connectionsClient.acceptConnection(any(), any()) } returns Tasks.forResult<Void>(null)
        every { connectionsClient.rejectConnection(any()) } returns Tasks.forResult<Void>(null)

        val manager = NearbyConnectionsManager(
            context = context,
            nowNativeClockSyncElapsedNanos = { 1L },
            connectionsClient = connectionsClient,
        )
        manager.startHosting("svc", "host", NearbyTransportStrategy.POINT_TO_POINT) { }

        lifecycleCallback.captured.onConnectionInitiated("ep-1", mockk<ConnectionInfo>(relaxed = true))
        lifecycleCallback.captured.onConnectionResult(
            "ep-1",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK)),
        )
        lifecycleCallback.captured.onConnectionInitiated("ep-2", mockk<ConnectionInfo>(relaxed = true))

        verify(exactly = 1) { connectionsClient.acceptConnection(any(), any()) }
        verify(exactly = 1) { connectionsClient.rejectConnection("ep-2") }
    }

    @Test
    fun `p2p star host accepts multiple incoming endpoints`() {
        val context = mockk<Context>(relaxed = true)
        val connectionsClient = mockk<ConnectionsClient>(relaxed = true)
        val lifecycleCallback = slot<ConnectionLifecycleCallback>()
        every {
            connectionsClient.startAdvertising(
                any<String>(),
                any<String>(),
                capture(lifecycleCallback),
                any<AdvertisingOptions>(),
            )
        } returns Tasks.forResult<Void>(null)
        every { connectionsClient.acceptConnection(any(), any()) } returns Tasks.forResult<Void>(null)
        every { connectionsClient.rejectConnection(any()) } returns Tasks.forResult<Void>(null)

        val manager = NearbyConnectionsManager(
            context = context,
            nowNativeClockSyncElapsedNanos = { 1L },
            connectionsClient = connectionsClient,
        )
        manager.startHosting("svc", "host", NearbyTransportStrategy.POINT_TO_STAR) { }

        lifecycleCallback.captured.onConnectionInitiated("ep-1", mockk<ConnectionInfo>(relaxed = true))
        lifecycleCallback.captured.onConnectionResult(
            "ep-1",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK)),
        )
        lifecycleCallback.captured.onConnectionInitiated("ep-2", mockk<ConnectionInfo>(relaxed = true))

        verify(exactly = 2) { connectionsClient.acceptConnection(any(), any()) }
        verify(exactly = 0) { connectionsClient.rejectConnection("ep-2") }
    }
}
