package dev.sphc.srccon.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import dev.sphc.srccon.core.RelayTarget

/** Enumeration uses only UsbManager descriptors and the library's permission-free prober. */
class UsbDeviceRepository(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prober = UsbSerialProber.getDefaultProber()

    fun scan(): List<UsbDeviceDescriptor> = manager.deviceList.values
        .map(::describe)
        .sortedWith(compareBy({ it.productName ?: it.manufacturerName ?: "" }, { it.key.deviceName }))

    fun findCurrent(key: UsbDeviceKey): UsbDevice? =
        manager.deviceList[key.deviceName]?.takeIf { it.key() == key }

    fun findCurrent(target: RelayTarget.Usb): UsbDevice? =
        manager.deviceList[target.deviceName]?.takeIf { it.key().matches(target) }

    fun describeCurrent(target: RelayTarget.Usb): UsbDeviceDescriptor? =
        findCurrent(target)?.let(::describe)

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    fun probe(device: UsbDevice): UsbSerialDriver? = prober.probeDevice(device)

    fun describe(device: UsbDevice): UsbDeviceDescriptor {
        val permissionGranted = manager.hasPermission(device)
        // UsbDevice.serialNumber can throw SecurityException without USB permission.
        val serial = if (permissionGranted) runCatching { device.serialNumber }.getOrNull() else null
        val driver = runCatching { prober.probeDevice(device) }.getOrNull()
        return UsbDeviceDescriptor(
            key = device.key(),
            productName = runCatching { device.productName }.getOrNull(),
            manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
            serialNumber = serial,
            driverFamily = driver?.family(),
            portCount = driver?.ports?.size ?: 0,
            permissionGranted = permissionGranted,
            deviceClass = device.deviceClass,
            interfaceClasses = (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass },
        )
    }
}

fun UsbDevice.key(): UsbDeviceKey = UsbDeviceKey(
    deviceName = deviceName,
    vendorId = vendorId,
    productId = productId,
    deviceId = deviceId,
)

fun UsbSerialDriver.family(): UsbSerialDriverFamily = when (this) {
    is Ch34xSerialDriver -> UsbSerialDriverFamily.CH34X
    is FtdiSerialDriver -> UsbSerialDriverFamily.FTDI
    is Cp21xxSerialDriver -> UsbSerialDriverFamily.CP210X
    is ProlificSerialDriver -> UsbSerialDriverFamily.PL2303
    is CdcAcmSerialDriver -> UsbSerialDriverFamily.CDC_ACM
    else -> UsbSerialDriverFamily.OTHER
}

fun matchesUsbDetach(target: RelayTarget.Usb, detached: UsbDeviceKey): Boolean =
    detached.matches(target)
