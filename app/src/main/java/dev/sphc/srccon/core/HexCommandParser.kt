package dev.sphc.srccon.core

/** The Windows SeRelCam syntax: individual HEX bytes separated by whitespace, comma, semicolon, colon or hyphen. */
object HexCommandParser {
    const val MAX_COMMAND_BYTES = 64
    private val separators = Regex("[\\s,;:\\-]+")
    private val hexByte = Regex("[0-9a-fA-F]{1,2}")

    fun parse(text: String): ByteArray {
        if (text.isBlank()) throw HexCommandFormatException("HEX command cannot be empty.")
        val bytes = ArrayList<Byte>()
        for (raw in text.trim().split(separators)) {
            if (raw.isEmpty()) continue
            val token = if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2) else raw
            if (!hexByte.matches(token)) {
                throw HexCommandFormatException("Invalid HEX byte: $raw")
            }
            bytes += token.toInt(16).toByte()
            if (bytes.size > MAX_COMMAND_BYTES) {
                throw HexCommandFormatException("HEX command is too long (maximum $MAX_COMMAND_BYTES bytes).")
            }
        }
        if (bytes.isEmpty()) throw HexCommandFormatException("HEX command cannot be empty.")
        return bytes.toByteArray()
    }

    fun format(command: ByteArray): String = command.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}

class HexCommandFormatException(message: String) : IllegalArgumentException(message)
