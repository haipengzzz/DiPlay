package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Refresh only the selected bonded device; never scan, unpair, or downgrade security. */
internal object BluetoothServiceDiscovery {
    @Suppress("DEPRECATION")
    fun refresh(
        context: Context,
        device: BluetoothDevice,
        service: UUID,
        cancelled: () -> Boolean,
        report: (String) -> Unit,
        timeoutMillis: Long = 8_000,
    ) {
        if (cancelled()) throw IOException("Bluetooth service discovery cancelled")
        val completed = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_UUID) return
                val peer = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (peer?.address != device.address) return
                val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    ?.filterIsInstance<ParcelUuid>()
                report("Bluetooth SDP result services=${uuids?.size ?: "unknown"} " +
                    "iap2=${uuids?.any { it.uuid == service } ?: "unknown"}")
                completed.countDown()
            }
        }
        var registered = false
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
            if (Build.VERSION.SDK_INT >= 33) {
                // Bluetooth broadcasts originate from a privileged process, not our UID.
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else context.registerReceiver(receiver, filter)
            registered = true
            val started = device.fetchUuidsWithSdp()
            report("Bluetooth SDP requested=$started timeoutMs=$timeoutMillis")
            if (!started) return // Keep the normal secure RFCOMM lookup as fallback.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!completed.await(100, TimeUnit.MILLISECONDS)) {
                if (cancelled()) throw IOException("Bluetooth service discovery cancelled")
                if (System.nanoTime() >= deadline) {
                    report("Bluetooth SDP timed out; attempting secure RFCOMM lookup")
                    return
                }
            }
            if (cancelled()) throw IOException("Bluetooth service discovery cancelled")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Bluetooth service discovery interrupted", error)
        } catch (error: SecurityException) {
            report("Bluetooth SDP unavailable failureClass=SecurityException")
            // Normal connect will report its own permission failure, without requesting new authority.
        } catch (error: RuntimeException) {
            report("Bluetooth SDP unavailable failureClass=${error.javaClass.simpleName}")
            // OEM service-discovery failures must not prevent the ordinary secure lookup.
        } finally {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
