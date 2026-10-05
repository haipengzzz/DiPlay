package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.orchestration.ConnectionIoDiagnostics
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /** Returns null for unrelated broadcasts, malformed results, or non-configured devices. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device)
        } else {
            PermissionResult.Denied(device)
        }
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection ->
                val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                val transferred = connection.controlTransfer(
                    USB_VENDOR_DEVICE_IN,
                    CARPLAY_CONFIGURATION_REQUEST,
                    0,
                    CARPLAY_CONFIGURATION_INDEX,
                    response,
                    response.size,
                    CONTROL_TRANSFER_TIMEOUT_MILLIS,
                )
                if (transferred != response.size) {
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $transferred of ${response.size} bytes",
                    )
                }
                TransitionResult.ReenumerationRequested
            })
        }
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            val selection = selectUsbConfiguration(connection, configuration)
            Log.i(IphoneCarPlayConfiguration.TAG, "usbmux configuration=${configuration.id} selection=$selection")
            diagnostic("USBMUX configuration=${configuration.id} selection=$selection")
            if (selection == UsbConfigurationSelection.FAILED) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "setConfiguration ${configuration.id} reported failure; claiming anyway",
                )
            }
            val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbmux config=${configuration.id} iface=${usbMux.id} alt=${usbMux.alternateSetting} " +
                    "class=${usbMux.interfaceClass}/${usbMux.interfaceSubclass}/${usbMux.interfaceProtocol} " +
                    "endpoints=${usbMux.endpointCount} " +
                    "out=${describeUsbEndpoint(endpoints.first)} " +
                    "in=${describeUsbEndpoint(endpoints.second)}",
            )
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim USBMUX interface ${usbMux.id}")
            }
            claimedInterface = usbMux
            diagnostic("USBMUX claimed iface=${usbMux.id} alt=${usbMux.alternateSetting} " +
                "out=${describeUsbEndpoint(endpoints.first)} in=${describeUsbEndpoint(endpoints.second)}")
            return Iap2UsbSession(connection, endpoints.first, endpoints.second, usbMux, onDiagnostic)
        } catch (error: Throwable) {
            try {
                if (claimedInterface != null) connection.releaseInterface(claimedInterface)
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            } finally {
                connection.close()
            }
            throw error
        }
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    private fun diagnostic(message: String) { runCatching { onDiagnostic(message) } }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val claimedInterface: UsbInterface? = null,
    private val onDiagnostic: (String) -> Unit = {},
    private val requestFactory: () -> UsbRequest = { UsbRequest() },
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private var queueReplacementUsed = false
    private val readBuffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
    private val ioDiagnostics = ConnectionIoDiagnostics(report = { line ->
        diagnostic(line.replace("wired io", "wired usbmux io"))
    })
    private var firstWrite = true
    private var firstCompletedRead = true

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        val started = System.nanoTime()
        val transferred = connection.bulkTransfer(outEndpoint, data, data.size, timeoutMillis)
        if (firstWrite) {
            firstWrite = false
            diagnostic("USBMUX first write expectedBytes=${data.size} transferred=$transferred timeoutMs=$timeoutMillis")
        }
        ioDiagnostics.record(ConnectionIoDiagnostics.Operation.WRITE,
            if (transferred == data.size) ConnectionIoDiagnostics.Result.COMPLETED else ConnectionIoDiagnostics.Result.FAILED,
            (System.nanoTime() - started) / 1_000_000)
        if (transferred != data.size) {
            diagnostic("USBMUX write failed expectedBytes=${data.size} transferred=$transferred timeoutMs=$timeoutMillis")
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /** Returns null only when no completed USB request arrives before [timeoutMillis]. */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val started = System.nanoTime()
        var outcome = ConnectionIoDiagnostics.Result.FAILED
        var recorded = false
        fun recordResult() {
            if (recorded) return
            recorded = true
            ioDiagnostics.record(ConnectionIoDiagnostics.Operation.READ, outcome,
                (System.nanoTime() - started) / 1_000_000)
        }
        try {
            // Publish and queue atomically with close(); close must not miss a new request.
            val request = synchronized(stateLock) {
                checkOpenLocked()
                var current = readRequest ?: requestFactory().also {
                    readRequest = it
                    diagnostic("USBMUX read initialize ${requestDiagnostics(timeoutMillis, readBuffer.capacity())}")
                    if (!it.initialize(connection, inEndpoint)) {
                        throw failSession("Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})")
                    }
                }
                if (!readQueued) {
                    readBuffer.clear()
                    if (!current.queue(readBuffer)) {
                        if (queueReplacementUsed) {
                            throw failSession("Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis, readBuffer.capacity())})")
                        }
                        // queue(false) has not submitted a request. Replace only that idle
                        // request once, never cancel a pending read or retry partial data.
                        queueReplacementUsed = true
                        diagnostic("USBMUX idle read queue rejected; replacing request once")
                        readRequest = null
                        current.close()
                        val replacement = requestFactory()
                        if (replacement === current) {
                            throw failSession("Android could not queue USBMUX read request (replacement was not fresh)")
                        }
                        readRequest = replacement
                        current = replacement
                        readBuffer.clear()
                        if (!replacement.initialize(connection, inEndpoint) || !replacement.queue(readBuffer)) {
                            throw failSession("Android could not queue USBMUX read request after replacement (${requestDiagnostics(timeoutMillis, readBuffer.capacity())})")
                        }
                        diagnostic("USBMUX idle read request replacement queued successfully")
                    }
                    readQueued = true
                }
                current
            }
            val completed = try {
                connection.requestWait(timeoutMillis)
            } catch (_: TimeoutException) {
                checkOpen()
                // Leave the request and its buffer queued. Cancelling at every timeout can
                // consume a late handshake reply while draining the cancellation completion.
                outcome = ConnectionIoDiagnostics.Result.TIMED_OUT
                return@synchronized null
            }
            checkOpen()
            if (completed == null) {
                throw failSession("Android returned no USBMUX read request (${requestDiagnostics(timeoutMillis)})")
            }
            if (completed !== request) {
                throw failSession("Android completed an unexpected USB request")
            }
            readQueued = false
            outcome = ConnectionIoDiagnostics.Result.COMPLETED
            if (firstCompletedRead) {
                firstCompletedRead = false
                diagnostic("USBMUX first completed read bytes=${readBuffer.position()} timeoutMs=$timeoutMillis")
            }
            return@synchronized ByteArray(readBuffer.position()).also {
                readBuffer.flip()
                readBuffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            recordResult()
            close()
            throw error
        } catch (error: RuntimeException) {
            val failure = failSession("USBMUX read failed (${requestDiagnostics(timeoutMillis)})", error)
            recordResult()
            close()
            throw failure
        } finally {
            recordResult()
        }
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        runCatching { requestToCancel?.cancel() }
        try {
            claimedInterface?.let { connection.releaseInterface(it) }
        } catch (_: RuntimeException) {
            // Closing the connection is authoritative if explicit release fails.
        } finally {
            connection.close()
            // The connection close wakes requestWait. Do not free a request still being reaped.
            synchronized(readLock) {
                runCatching { requestToCancel?.close() }
            }
            ioDiagnostics.finish()
            diagnostic("USBMUX closed iface=${claimedInterface?.id ?: "none"}")
        }
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        diagnostic("USBMUX failure=$message causeClass=${cause?.javaClass?.simpleName ?: "none"}")
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun diagnostic(message: String) { runCatching { onDiagnostic(message) } }

    private fun requestDiagnostics(timeoutMillis: Long, bufferBytes: Int? = null): String = buildString {
        append("endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
        if (bufferBytes != null) append(" bufferBytes=").append(bufferBytes)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536
    }
}

private fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi I2C failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}
