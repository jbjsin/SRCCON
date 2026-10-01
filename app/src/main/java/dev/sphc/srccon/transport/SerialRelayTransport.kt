package dev.sphc.srccon.transport

import dev.sphc.srccon.core.RelayTarget
import dev.sphc.srccon.core.SerialConfiguration

/** One bounded open/configure/write/close attempt. Throw on timeout, partial write or any failure. */
fun interface SerialRelayTransport {
    suspend fun send(target: RelayTarget, serial: SerialConfiguration, command: ByteArray)
}
