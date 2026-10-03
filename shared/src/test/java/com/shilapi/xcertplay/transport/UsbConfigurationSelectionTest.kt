package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDeviceConnection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class UsbConfigurationSelectionTest {
    private val connection = mock(UsbDeviceConnection::class.java)
    private val configuration = mock(UsbConfiguration::class.java).also {
        `when`(it.id).thenReturn(6)
    }

    private fun activeConfiguration(id: Int, length: Int = 1) {
        `when`(connection.controlTransfer(eq(0x80), eq(0x08), eq(0), eq(0), any(ByteArray::class.java), eq(1), eq(1_000)))
            .thenAnswer { it.getArgument<ByteArray>(4)[0] = id.toByte(); length }
    }

    @Test fun doesNotResetAnAlreadyActiveCarPlayConfiguration() {
        activeConfiguration(6)
        assertEquals(UsbConfigurationSelection.REUSED, selectUsbConfiguration(connection, configuration))
        verify(connection, never()).setConfiguration(configuration)
    }

    @Test fun switchesFromAnotherActiveConfiguration() {
        activeConfiguration(1)
        `when`(connection.setConfiguration(configuration)).thenReturn(true)
        assertEquals(UsbConfigurationSelection.SELECTED, selectUsbConfiguration(connection, configuration))
        verify(connection).setConfiguration(configuration)
    }

    @Test fun failedQueryFallsBackToExistingConfigurationSelection() {
        activeConfiguration(6, -1)
        `when`(connection.setConfiguration(configuration)).thenReturn(true)
        assertEquals(UsbConfigurationSelection.SELECTED, selectUsbConfiguration(connection, configuration))
    }

    @Test fun zeroLengthQueryMustNotProveConfigurationIsActive() {
        activeConfiguration(6, 0)
        assertEquals(UsbConfigurationSelection.FAILED, selectUsbConfiguration(connection, configuration))
        verify(connection).setConfiguration(configuration)
    }
}
