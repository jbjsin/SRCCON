package dev.sphc.srccon.core

sealed interface RelayTarget {
    /** deviceName and deviceId identify the current Android attachment, not a persistent USB identity. */
    data class Usb(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val deviceId: Int,
        val portIndex: Int = 0,
    ) : RelayTarget

    /** An explicitly selected software-only relay for development and demonstration. */
    data object Demo : RelayTarget
}

enum class SerialParity { NONE, ODD, EVEN, MARK, SPACE }

data class SerialConfiguration(
    val baudRate: Int,
    val dataBits: Int = 8,
    val parity: SerialParity = SerialParity.NONE,
    val stopBits: Int = 1,
    val dtr: Boolean = false,
    val rts: Boolean = false,
)

/** Copies byte arrays both on entry and exit, so a running operation cannot observe edited commands. */
class RelayConfigurationSnapshot(
    val target: RelayTarget,
    val serial: SerialConfiguration,
    val profileId: String,
    onCommand: ByteArray,
    offCommand: ByteArray,
) {
    private val onBytes = onCommand.copyOf()
    private val offBytes = offCommand.copyOf()

    val onCommand: ByteArray get() = onBytes.copyOf()
    val offCommand: ByteArray get() = offBytes.copyOf()

    internal fun command(isOn: Boolean): ByteArray = if (isOn) onBytes.copyOf() else offBytes.copyOf()

    fun validate() {
        require(profileId.isNotBlank()) { "A relay profile must be selected." }
        when (val selected = target) {
            is RelayTarget.Usb -> {
                require(selected.deviceName.isNotBlank()) { "A USB device must be selected." }
                require(selected.vendorId in 0..0xFFFF && selected.productId in 0..0xFFFF) { "Invalid USB VID/PID." }
                require(selected.deviceId >= 0 && selected.portIndex >= 0) { "Invalid USB device or port." }
            }
            RelayTarget.Demo -> Unit
        }
        require(serial.baudRate in 1..4_000_000) { "Invalid baud rate." }
        require(serial.dataBits in 5..8) { "Invalid data bits." }
        require(serial.stopBits in 1..2) { "Invalid stop bits." }
        require(onBytes.size in 1..HexCommandParser.MAX_COMMAND_BYTES) { "Invalid ON command length." }
        require(offBytes.size in 1..HexCommandParser.MAX_COMMAND_BYTES) { "Invalid OFF command length." }
    }
}
