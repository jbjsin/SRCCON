package dev.sphc.srccon.usb

import dev.sphc.srccon.core.RelayTarget
import java.nio.charset.StandardCharsets
import java.util.Base64

/** An attachment identity, never a persistent device identity. */
data class UsbDeviceKey(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val deviceId: Int,
) {
    fun matches(target: RelayTarget.Usb): Boolean =
        deviceName == target.deviceName &&
            vendorId == target.vendorId &&
            productId == target.productId &&
            deviceId == target.deviceId

    fun toTarget(portIndex: Int): RelayTarget.Usb =
        RelayTarget.Usb(deviceName, vendorId, productId, deviceId, portIndex)
}

enum class UsbSerialDriverFamily(val displayName: String) {
    CH34X("CH34x"),
    FTDI("FTDI"),
    CP210X("CP210x"),
    PL2303("PL2303"),
    CDC_ACM("CDC ACM"),
    OTHER("Other serial driver"),
}

/** All fields are obtained from descriptors or UsbManager.hasPermission; scanning never opens a device. */
data class UsbDeviceDescriptor(
    val key: UsbDeviceKey,
    val productName: String?,
    val manufacturerName: String?,
    val serialNumber: String?,
    val driverFamily: UsbSerialDriverFamily?,
    val portCount: Int,
    val permissionGranted: Boolean,
    val deviceClass: Int,
    val interfaceClasses: List<Int>,
) {
    val supported: Boolean get() = driverFamily != null && portCount > 0

    fun targetForPort(portIndex: Int): RelayTarget.Usb {
        require(supported) { "This USB device has no supported serial port" }
        require(portIndex in 0 until portCount) { "Serial port index is out of range" }
        return key.toTarget(portIndex)
    }

    fun fingerprintHint(): UsbDeviceFingerprint = UsbDeviceFingerprint(
        vendorId = key.vendorId,
        productId = key.productId,
        manufacturerName = manufacturerName,
        productName = productName,
        serialNumber = serialNumber,
    )
}

/** A hint for display only. It must never select or connect a device. */
data class UsbDeviceFingerprint(
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
) {
    fun encode(): String = listOf(
        "v1",
        vendorId.toString(),
        productId.toString(),
        encodeText(manufacturerName),
        encodeText(productName),
        encodeText(serialNumber),
    ).joinToString(":")

    companion object {
        fun decode(value: String?): UsbDeviceFingerprint? {
            if (value == null) return null
            val fields = value.split(':')
            if (fields.size != 6 || fields[0] != "v1") return null
            val vendorId = fields[1].toIntOrNull()?.takeIf { it in 0..0xffff } ?: return null
            val productId = fields[2].toIntOrNull()?.takeIf { it in 0..0xffff } ?: return null
            return runCatching {
                UsbDeviceFingerprint(
                    vendorId,
                    productId,
                    decodeText(fields[3]),
                    decodeText(fields[4]),
                    decodeText(fields[5]),
                )
            }.getOrNull()
        }

        private fun encodeText(value: String?): String = value?.let {
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        } ?: "~"

        private fun decodeText(value: String): String? =
            if (value == "~") null else String(
                Base64.getUrlDecoder().decode(value),
                StandardCharsets.UTF_8,
            )
    }
}

enum class PreviousDeviceHint {
    EXACT_SERIAL,
    POSSIBLE_MODEL_MATCH,
}

/** Never marks multiple identical devices as the previously used one. */
fun previousDeviceHints(
    saved: UsbDeviceFingerprint?,
    devices: List<UsbDeviceDescriptor>,
): Map<UsbDeviceKey, PreviousDeviceHint> {
    if (saved == null) return emptyMap()
    val sameModel = devices.filter { candidate ->
        candidate.key.vendorId == saved.vendorId &&
            candidate.key.productId == saved.productId &&
            (saved.manufacturerName == null ||
                candidate.manufacturerName == null ||
                candidate.manufacturerName == saved.manufacturerName) &&
            (saved.productName == null ||
                candidate.productName == null || candidate.productName == saved.productName)
    }
    if (saved.serialNumber != null) {
        val exact = sameModel.filter { it.serialNumber == saved.serialNumber }
        if (exact.size == 1) return mapOf(exact.single().key to PreviousDeviceHint.EXACT_SERIAL)
        // A readable, different serial disproves the hint. Fall back only when the current
        // attachment's serial cannot be read because permission is not available yet.
        if (sameModel.size != 1 || sameModel.single().serialNumber != null) return emptyMap()
    }
    return if (sameModel.size == 1) {
        mapOf(sameModel.single().key to PreviousDeviceHint.POSSIBLE_MODEL_MATCH)
    } else {
        emptyMap()
    }
}
