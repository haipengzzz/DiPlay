package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BluetoothServiceDiscoveryTest {
    private val service = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    private val device = mock(BluetoothDevice::class.java).also {
        `when`(it.address).thenReturn("01:02:03:04:05:06")
    }
    private val context = mock(Context::class.java)

    @Test fun unavailableSdpStillUnregistersAndAllowsNormalConnection() {
        `when`(device.fetchUuidsWithSdp()).thenReturn(false)
        val logs = mutableListOf<String>()
        BluetoothServiceDiscovery.refresh(context, device, service, { false }, logs::add)
        verify(context).unregisterReceiver(any(BroadcastReceiver::class.java))
        assertTrue(logs.any { it.contains("requested=false") })
    }

    @Test fun selectedPeerBroadcastCompletesAndReportsOnlyMetadata() {
        var receiver: BroadcastReceiver? = null
        `when`(context.registerReceiver(any(BroadcastReceiver::class.java), any(IntentFilter::class.java)))
            .thenAnswer { receiver = it.getArgument(0); null }
        `when`(device.fetchUuidsWithSdp()).thenAnswer {
            receiver!!.onReceive(context, Intent(BluetoothDevice.ACTION_UUID)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BluetoothDevice.EXTRA_UUID, arrayOf(ParcelUuid(service))))
            true
        }
        val logs = mutableListOf<String>()
        BluetoothServiceDiscovery.refresh(context, device, service, { false }, logs::add)
        assertTrue(logs.any { it.contains("iap2=true") })
        assertFalse(logs.joinToString().contains(device.address))
        verify(context).unregisterReceiver(receiver)
    }

    @Test fun timeoutIsBoundedAndCleansReceiver() {
        `when`(device.fetchUuidsWithSdp()).thenReturn(true)
        val logs = mutableListOf<String>()
        BluetoothServiceDiscovery.refresh(context, device, service, { false }, logs::add, timeoutMillis = 1)
        assertTrue(logs.any { it.contains("timed out") })
        verify(context).unregisterReceiver(any(BroadcastReceiver::class.java))
    }

    @Test fun anotherPeerBroadcastDoesNotCompleteSelectedDeviceDiscovery() {
        var receiver: BroadcastReceiver? = null
        `when`(context.registerReceiver(any(BroadcastReceiver::class.java), any(IntentFilter::class.java)))
            .thenAnswer { receiver = it.getArgument(0); null }
        val other = mock(BluetoothDevice::class.java)
        `when`(other.address).thenReturn("01:02:03:04:05:07")
        `when`(device.fetchUuidsWithSdp()).thenAnswer {
            receiver!!.onReceive(context, Intent(BluetoothDevice.ACTION_UUID)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, other)
                .putExtra(BluetoothDevice.EXTRA_UUID, arrayOf(ParcelUuid(service))))
            true
        }
        val logs = mutableListOf<String>()
        BluetoothServiceDiscovery.refresh(context, device, service, { false }, logs::add, timeoutMillis = 1)
        assertFalse(logs.any { it.contains("SDP result") })
        assertTrue(logs.any { it.contains("timed out") })
        verify(context).unregisterReceiver(receiver)
    }

    @Test fun OemDiscoveryFailureDoesNotPreventNormalConnectAndCleansReceiver() {
        `when`(device.fetchUuidsWithSdp()).thenThrow(NullPointerException())
        val logs = mutableListOf<String>()
        BluetoothServiceDiscovery.refresh(context, device, service, { false }, logs::add)
        assertTrue(logs.any { it.contains("failureClass=NullPointerException") })
        verify(context).unregisterReceiver(any(BroadcastReceiver::class.java))
    }

    @Test fun cancellationAfterStartingDiscoveryCleansReceiver() {
        var cancelled = false
        `when`(device.fetchUuidsWithSdp()).thenAnswer { cancelled = true; true }
        try {
            BluetoothServiceDiscovery.refresh(context, device, service, { cancelled }, {})
            fail("Expected cancellation")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("cancelled"))
        }
        verify(context).unregisterReceiver(any(BroadcastReceiver::class.java))
    }

    @Test @Config(sdk = [33]) fun android13UsesExportedReceiverForSystemBluetoothBroadcasts() {
        `when`(device.fetchUuidsWithSdp()).thenReturn(false)
        BluetoothServiceDiscovery.refresh(context, device, service, { false }, {})
        verify(context).registerReceiver(any(BroadcastReceiver::class.java), any(IntentFilter::class.java), eq(Context.RECEIVER_EXPORTED))
        verify(context).unregisterReceiver(any(BroadcastReceiver::class.java))
    }
}
