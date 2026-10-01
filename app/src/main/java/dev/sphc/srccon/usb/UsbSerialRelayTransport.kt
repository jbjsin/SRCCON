package dev.sphc.srccon.usb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import dev.sphc.srccon.core.HexCommandParser
import dev.sphc.srccon.core.RelayTarget
import dev.sphc.srccon.core.SerialConfiguration
import dev.sphc.srccon.core.SerialParity
import dev.sphc.srccon.transport.SerialRelayTransport
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Exactly one command attempt. The coordinator owns retry, serialization, and operation generation.
 * No serial interface is claimed until this method is called for a previously authorized target.
 */
class UsbSerialRelayTransport(
    context: Context,
    private val devices: UsbDeviceRepository,
    private val openTimeoutMillis: Long = 2_000,
    private val writeTimeoutMillis: Int = 1_000,
) : SerialRelayTransport {
    private val manager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    init {
        require(openTimeoutMillis in 1..30_000)
        require(writeTimeoutMillis in 1..30_000)
    }

    override suspend fun send(target: RelayTarget, serial: SerialConfiguration, command: ByteArray) {
        require(target is RelayTarget.Usb) { "The USB transport requires a USB target" }
        require(command.size in 1..HexCommandParser.MAX_COMMAND_BYTES) { "Invalid relay command length" }
        validateSerial(serial)
        val bytes = command.copyOf()
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val opened = acquireBounded(target)
            opened.use { handle ->
                currentCoroutineContext().ensureActive()
                handle.port.setParameters(
                    serial.baudRate,
                    serial.dataBits,
                    serial.stopBits,
                    serial.parity.toUsbParity(),
                )
                handle.port.setDTR(serial.dtr)
                handle.port.setRTS(serial.rts)
                currentCoroutineContext().ensureActive()
                // In usb-serial-for-android 3.11.0 write() returns void only after all bytes
                // are written; a timeout/partial write throws SerialTimeoutException.
                handle.port.write(bytes, writeTimeoutMillis)
            }
        }
    }

    private fun acquireBounded(target: RelayTarget.Usb): OpenedPort {
        val future = CompletableFuture.supplyAsync({ openSelectedPort(target) }, openExecutor)
        return try {
            future.get(openTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            // openDevice/port.open can be blocking. If it completes late, close it; never write.
            future.thenAccept { runCatching { it.close() } }
            throw IOException("Timed out opening the selected USB serial port", error)
        } catch (error: InterruptedException) {
            future.thenAccept { runCatching { it.close() } }
            Thread.currentThread().interrupt()
            throw IOException("Interrupted opening the selected USB serial port", error)
        } catch (error: ExecutionException) {
            throw IOException("Unable to open the selected USB device; it may already be in use", error.cause)
        }
    }

    private fun openSelectedPort(target: RelayTarget.Usb): OpenedPort {
        val device = devices.findCurrent(target)
            ?: throw IOException("Selected USB device is detached")
        if (!devices.hasPermission(device)) throw IOException("USB permission is not granted")
        val driver = devices.probe(device)
            ?: throw IOException("No supported serial driver for selected USB device")
        val port = driver.ports.getOrNull(target.portIndex)
            ?: throw IOException("Selected USB serial port is unavailable")
        val connection = manager.openDevice(device)
            ?: throw IOException("Unable to open selected USB device; it may already be in use")
        try {
            port.open(connection)
            return OpenedPort(port, connection)
        } catch (error: Throwable) {
            runCatching { port.close() }
            connection.close()
            throw error
        }
    }

    private fun validateSerial(serial: SerialConfiguration) {
        require(serial.baudRate in 1..4_000_000) { "Invalid baud rate" }
        require(serial.dataBits in 5..8) { "Invalid data bits" }
        require(serial.stopBits in 1..2) { "Invalid stop bits" }
    }

    private fun SerialParity.toUsbParity(): Int = when (this) {
        SerialParity.NONE -> UsbSerialPort.PARITY_NONE
        SerialParity.ODD -> UsbSerialPort.PARITY_ODD
        SerialParity.EVEN -> UsbSerialPort.PARITY_EVEN
        SerialParity.MARK -> UsbSerialPort.PARITY_MARK
        SerialParity.SPACE -> UsbSerialPort.PARITY_SPACE
    }

    private class OpenedPort(
        val port: UsbSerialPort,
        private val connection: UsbDeviceConnection,
    ) : Closeable {
        override fun close() {
            try {
                port.close()
            } finally {
                connection.close()
            }
        }
    }

    private companion object {
        // A timed-out vendor open can remain blocked. Keep room for a later safety OFF while
        // rejecting excess work instead of queuing stale open attempts that might run later.
        val openExecutor = ThreadPoolExecutor(
            0,
            4,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "srccon-usb-open").apply { isDaemon = true } },
        )
    }
}
