package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*

/** Exercises the real session, with only the Android USB boundary mocked. */
class Iap2UsbSessionLifecycleTest {
    private lateinit var connection: UsbDeviceConnection
    private lateinit var request: UsbRequest
    private lateinit var usbInterface: UsbInterface
    private lateinit var session: Iap2UsbSession
    private lateinit var buffer: ByteBuffer
    private var factories = 0
    private val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        connection = mock(UsbDeviceConnection::class.java)
        request = mock(UsbRequest::class.java)
        usbInterface = mock(UsbInterface::class.java)
        val input = mock(UsbEndpoint::class.java)
        `when`(request.initialize(connection, input)).thenReturn(true)
        `when`(request.queue(any(ByteBuffer::class.java))).thenAnswer {
            buffer = it.getArgument(0)
            true
        }
        session = Iap2UsbSession(connection, mock(UsbEndpoint::class.java), input, usbInterface,
            onDiagnostic = diagnostics::add) {
            factories++
            request
        }
    }

    @Test fun lateReplySurvivesRepeatedTimeoutsWithoutCancelOrRequeue() {
        `when`(connection.requestWait(anyLong())).thenAnswer { throw TimeoutException() }
        assertNull(session.read(10))
        assertNull(session.read(10))
        assertTrue(diagnostics.any { it.contains("result=TIMED_OUT") })
        assertFalse(diagnostics.any { it.contains("result=FAILED") })
        verify(request, never()).cancel()
        verify(request, never()).close()
        doAnswer { buffer.put(byteArrayOf(1, 2, 3)); request }.`when`(connection).requestWait(anyLong())
        assertArrayEquals(byteArrayOf(1, 2, 3), session.read(10))
        assertEquals(1, factories)
        verify(request, times(1)).queue(any(ByteBuffer::class.java))
        session.close()
        verify(request).cancel()
        verify(request).close()
        assertTrue(diagnostics.any { it.contains("first completed read bytes=3") })
        assertTrue(diagnostics.any { it.contains("io final") && it.contains("readTimeouts=2") })
    }

    @Test fun completedRequestIsReusedWithClearedBuffer() {
        var reads = 0
        `when`(connection.requestWait(anyLong())).thenAnswer {
            if (reads++ == 0) buffer.put(byteArrayOf(1, 2, 3)) else buffer.put(4.toByte())
            request
        }
        try {
            assertArrayEquals(byteArrayOf(1, 2, 3), session.read(10))
            assertArrayEquals(byteArrayOf(4), session.read(10))
            assertEquals(1, factories)
            verify(request, times(2)).queue(any(ByteBuffer::class.java))
        } finally { session.close() }
    }

    @Test fun nullCompletionIsFatalAndReleasesInterfaceImmediately() {
        expectFailure("Android returned no USBMUX read request") { session.read(10) }
        verify(connection).releaseInterface(usbInterface)
        verify(connection).close()
        verify(request).close()
        assertTrue(diagnostics.any { it.contains("USBMUX failure=") })
        assertTrue(diagnostics.any { it.contains("io final") && it.contains("failures=1") })
        expectFailure("Android returned no USBMUX read request") { session.read(10) }
        session.close()
        verify(connection, times(1)).close()
    }

    @Test fun foreignCompletionIsFatal() {
        `when`(connection.requestWait(anyLong())).thenReturn(mock(UsbRequest::class.java))
        expectFailure("unexpected USB request") { session.read(10) }
        verify(connection).close()
    }

    @Test fun failedQueueClosesRequestAndConnection() {
        `when`(request.queue(any(ByteBuffer::class.java))).thenReturn(false)
        expectFailure("could not queue") { session.read(10) }
        verify(request).close()
        verify(connection).close()
    }

    @Test fun idleQueueRejectionCanRecoverWithOneFreshRequest() {
        val replacement = mock(UsbRequest::class.java)
        val input = mock(UsbEndpoint::class.java)
        `when`(request.initialize(connection, input)).thenReturn(true)
        `when`(request.queue(any(ByteBuffer::class.java))).thenReturn(false)
        `when`(replacement.initialize(connection, input)).thenReturn(true)
        `when`(replacement.queue(any(ByteBuffer::class.java))).thenAnswer {
            buffer = it.getArgument(0); true
        }
        var calls = 0
        session = Iap2UsbSession(connection, mock(UsbEndpoint::class.java), input, usbInterface,
            onDiagnostic = diagnostics::add) { if (calls++ == 0) request else replacement }
        `when`(connection.requestWait(anyLong())).thenAnswer {
            buffer.put(byteArrayOf(4, 5)); replacement
        }
        assertArrayEquals(byteArrayOf(4, 5), session.read(10))
        assertEquals(2, calls)
        verify(request).close()
        verify(request, never()).cancel()
        verify(connection, never()).close()
        session.close()
        verify(replacement).close()
        verify(connection).close()
        assertTrue(diagnostics.any { it.contains("replacement queued successfully") })
    }

    @Test fun replacementBudgetIsFiniteAndSecondFailureClosesEverything() {
        val replacement = mock(UsbRequest::class.java)
        val input = mock(UsbEndpoint::class.java)
        `when`(request.initialize(connection, input)).thenReturn(true)
        `when`(request.queue(any(ByteBuffer::class.java))).thenReturn(false)
        `when`(replacement.initialize(connection, input)).thenReturn(true)
        `when`(replacement.queue(any(ByteBuffer::class.java))).thenAnswer {
            buffer = it.getArgument(0); true
        }.thenReturn(false)
        var calls = 0
        session = Iap2UsbSession(connection, mock(UsbEndpoint::class.java), input, usbInterface,
            onDiagnostic = diagnostics::add) { if (calls++ == 0) request else replacement }
        `when`(connection.requestWait(anyLong())).thenAnswer { buffer.put(1.toByte()); replacement }
        assertArrayEquals(byteArrayOf(1), session.read(10))
        expectFailure("could not queue") { session.read(10) }
        assertEquals(2, calls)
        verify(request).close()
        verify(replacement).close()
        verify(connection).close()
    }

    @Test fun detachedDeviceCannotBeHiddenByFreshRequestFailure() {
        val replacement = mock(UsbRequest::class.java)
        val input = mock(UsbEndpoint::class.java)
        `when`(request.initialize(connection, input)).thenReturn(true)
        `when`(request.queue(any(ByteBuffer::class.java))).thenReturn(false)
        `when`(replacement.initialize(connection, input)).thenReturn(false)
        var calls = 0
        session = Iap2UsbSession(connection, mock(UsbEndpoint::class.java), input, usbInterface) {
            if (calls++ == 0) request else replacement
        }
        expectFailure("after replacement") { session.read(10) }
        verify(request).close()
        verify(replacement).close()
        verify(connection).close()
        verify(connection, never()).requestWait(anyLong())
    }

    @Test fun throwingInitializeDoesNotLeakRequest() {
        `when`(request.initialize(any(UsbDeviceConnection::class.java), any(UsbEndpoint::class.java)))
            .thenThrow(IllegalStateException("OEM failure"))
        expectFailure("USBMUX read failed") { session.read(10) }
        verify(request).close()
        verify(connection).close()
    }

    @Test fun closeStillClosesConnectionIfInterfaceReleaseThrows() {
        `when`(connection.releaseInterface(usbInterface)).thenThrow(IllegalStateException())
        session.close()
        session.close()
        verify(connection).close()
        expectFailure("session is closed") { session.read(10) }
        assertEquals(0, factories)
    }

    @Test fun closeDuringWaitWakesReaderWithoutFreeingAnActiveRequest() {
        val entered = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        `when`(connection.requestWait(anyLong())).thenAnswer {
            entered.countDown()
            assertTrue(detached.await(1, TimeUnit.SECONDS))
            request
        }
        doAnswer { detached.countDown(); null }.`when`(connection).close()
        val reader = Thread {
            try { session.read(1_000) } catch (error: Throwable) { failure.set(error) }
        }
        reader.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            session.close()
            reader.join(1_000)
            assertFalse(reader.isAlive)
            assertTrue(failure.get() is IphoneUsbException.DeviceUnavailable)
            verify(request).close()
            verify(connection).close()
        } finally { detached.countDown(); reader.join(1_000) }
    }

    private fun expectFailure(fragment: String, operation: () -> Unit) {
        try { operation(); fail("expected USB session failure") }
        catch (error: IphoneUsbException.DeviceUnavailable) {
            assertTrue(error.message, error.message.orEmpty().contains(fragment))
        }
    }
}
