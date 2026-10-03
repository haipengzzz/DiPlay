package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDeviceConnection

internal enum class UsbConfigurationSelection { REUSED, SELECTED, FAILED }

/** Descriptors describe available configurations, not which one is currently active. */
internal fun selectUsbConfiguration(
    connection: UsbDeviceConnection,
    configuration: UsbConfiguration,
): UsbConfigurationSelection {
    val active = ByteArray(1)
    // Standard device-to-host GET_CONFIGURATION. Do not reset an already active config:
    // SET_CONFIGURATION can reset endpoints and disturb an existing OEM USB owner.
    val length = connection.controlTransfer(0x80, 0x08, 0, 0, active, 1, 1_000)
    if (length == 1 && (active[0].toInt() and 0xff) == configuration.id) {
        return UsbConfigurationSelection.REUSED
    }
    return if (connection.setConfiguration(configuration)) UsbConfigurationSelection.SELECTED
    else UsbConfigurationSelection.FAILED
}
