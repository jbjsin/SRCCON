package dev.sphc.srccon.transport

import dev.sphc.srccon.core.RelayTarget
import dev.sphc.srccon.core.SerialConfiguration
import java.io.IOException

/** Software-only transport. Recording a send never opens or energizes USB hardware. */
class FakeSerialRelayTransport : SerialRelayTransport {
    class SentCommand(
        val target: RelayTarget,
        val serial: SerialConfiguration,
        command: ByteArray,
    ) {
        private val bytes = command.copyOf()
        val command: ByteArray get() = bytes.copyOf()
    }

    private val lock = Any()
    private val recorded = mutableListOf<SentCommand>()
    private val failures = ArrayDeque<Throwable?>()

    /** Runs after an attempt has been recorded and before its scripted failure is thrown. */
    var onSend: (suspend (SentCommand) -> Unit)? = null

    val attempts: List<SentCommand> get() = synchronized(lock) { recorded.toList() }

    fun enqueueSuccess() = synchronized(lock) { failures.addLast(null) }
    fun enqueueFailure(error: Throwable = IOException("Scripted serial failure")) = synchronized(lock) {
        failures.addLast(error)
    }

    override suspend fun send(target: RelayTarget, serial: SerialConfiguration, command: ByteArray) {
        val attempt = SentCommand(target, serial, command)
        val failure = synchronized(lock) {
            recorded += attempt
            if (failures.isEmpty()) null else failures.removeFirst()
        }
        onSend?.invoke(attempt)
        if (failure != null) throw failure
    }
}
