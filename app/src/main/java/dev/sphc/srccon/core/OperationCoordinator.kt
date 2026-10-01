package dev.sphc.srccon.core

import dev.sphc.srccon.transport.SerialRelayTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OperationOwner { MANUAL, SINGLE }
enum class OperationPhase { IDLE, PREFLIGHT, MANUAL_ON, SINGLE_EXPOSURE, STOPPING, FAULT }

/** These values describe command transmission, never a measured physical contact. */
enum class PhysicalRelayState { UNKNOWN, ON_COMMAND_SENT, OFF_COMMAND_SENT }

data class OperationState(
    val phase: OperationPhase = OperationPhase.IDLE,
    val owner: OperationOwner? = null,
    val physicalRelayState: PhysicalRelayState = PhysicalRelayState.UNKNOWN,
    val target: RelayTarget? = null,
    val generation: Long = 0,
    val startedAtElapsedMillis: Long? = null,
    val deadlineElapsedMillis: Long? = null,
    val message: String? = null,
)

sealed interface OperationResult {
    data object Started : OperationResult
    data object Stopped : OperationResult
    data class Rejected(val reason: String) : OperationResult
    data class Failed(val reason: String) : OperationResult
}

fun interface MonotonicClock { fun elapsedRealtimeMillis(): Long }
fun interface Sleeper { suspend fun sleep(millis: Long) }

/** Must check explicit connection, current attachment, USB permission, driver and port before any command. */
fun interface TargetReadiness { suspend fun requireReady(target: RelayTarget) }

/** Durable may-be-energized marker; implementation may store only a Boolean. */
interface RiskMarkerStore {
    suspend fun markAtRisk(snapshot: RelayConfigurationSnapshot)
    suspend fun clear()
    suspend fun isAtRisk(): Boolean
}

/** Service-owned operation state machine. No Android UI or USB API is required by this class. */
class OperationCoordinator(
    private val scope: CoroutineScope,
    private val transport: SerialRelayTransport,
    private val targetReadiness: TargetReadiness,
    private val clock: MonotonicClock,
    private val sleeper: Sleeper,
    private val riskMarkerStore: RiskMarkerStore,
) {
    companion object {
        const val MAX_SINGLE_EXPOSURE_MILLIS: Long = 86_400_000L
    }

    private data class ActiveOperation(
        val token: Long,
        val owner: OperationOwner,
        val snapshot: RelayConfigurationSnapshot,
    )

    private val gate = Any()
    private val commandMutex = Mutex()
    private val markerMutex = Mutex()
    private var generation = 0L
    private var active: ActiveOperation? = null
    private var startJob: Job? = null
    private var singleJob: Job? = null
    private val mutableState = MutableStateFlow(OperationState())
    val state: StateFlow<OperationState> = mutableState.asStateFlow()

    /** Reads durable risk state without opening or selecting USB hardware. Call on service creation. */
    suspend fun restoreRiskMarker(): Boolean {
        val risky = try {
            markerMutex.withLock { riskMarkerStore.isAtRisk() }
        } catch (error: Exception) {
            faultWhenUnowned("Cannot read relay safety marker: ${error.safeMessage()}")
            return true
        }
        if (risky) synchronized(gate) {
            if (active == null && mutableState.value.phase == OperationPhase.IDLE) {
                generation++
                mutableState.value = OperationState(
                    phase = OperationPhase.FAULT,
                    physicalRelayState = PhysicalRelayState.UNKNOWN,
                    generation = generation,
                    message = "Previous relay operation ended without a confirmed OFF transmission.",
                )
            }
        }
        return risky
    }

    suspend fun startManual(snapshot: RelayConfigurationSnapshot): OperationResult =
        startOperation(snapshot, OperationOwner.MANUAL, durationMillis = null, maximumDurationMillis = null)

    suspend fun startSingle(
        snapshot: RelayConfigurationSnapshot,
        durationMillis: Long,
        maximumDurationMillis: Long,
    ): OperationResult = startOperation(snapshot, OperationOwner.SINGLE, durationMillis, maximumDurationMillis)

    suspend fun manualOff(): OperationResult {
        if (synchronized(gate) { active?.owner } != OperationOwner.MANUAL) {
            return OperationResult.Rejected("Manual does not own the relay.")
        }
        return stop()
    }

    /** STOP invalidates the old token before waiting on an in-flight transport attempt. */
    suspend fun stop(): OperationResult {
        val op: ActiveOperation
        val stopGeneration: Long
        synchronized(gate) {
            op = active ?: return OperationResult.Rejected("No active operation.")
            if (!isCurrentLocked(op.token) || mutableState.value.phase == OperationPhase.STOPPING) {
                return OperationResult.Rejected("Safety OFF is already in progress.")
            }
            generation++
            stopGeneration = generation
            mutableState.value = mutableState.value.copy(
                phase = OperationPhase.STOPPING,
                generation = generation,
                message = "STOP requested; sending safety OFF.",
            )
            startJob?.cancel()
            startJob = null
            singleJob?.cancel()
            singleJob = null
        }
        return withContext(NonCancellable) { settleWithOff(op, stopGeneration, completionMessage = null) }
    }

    /** Called only for an actually detached active USB attachment. No reconnect or USB command is attempted. */
    fun onUsbDetached(target: RelayTarget.Usb) {
        synchronized(gate) {
            val op = active ?: return
            if (op.snapshot.target != target) return
            generation++
            startJob?.cancel()
            startJob = null
            singleJob?.cancel()
            singleJob = null
            active = null
            mutableState.value = OperationState(
                phase = OperationPhase.FAULT,
                physicalRelayState = PhysicalRelayState.UNKNOWN,
                target = target,
                generation = generation,
                message = "USB relay detached before OFF could be transmitted; physical state unknown.",
            )
        }
    }

    /** Explicit user-visible OFF after Connect, including recovery from a persisted risk marker. */
    suspend fun safetyOff(snapshot: RelayConfigurationSnapshot): OperationResult {
        try {
            snapshot.validate()
        } catch (error: IllegalArgumentException) {
            return OperationResult.Rejected(error.message ?: "Invalid relay configuration.")
        }
        val offGeneration: Long
        synchronized(gate) {
            if (active != null || mutableState.value.phase == OperationPhase.STOPPING) {
                return OperationResult.Rejected("Another operation owns the relay.")
            }
            generation++
            offGeneration = generation
            mutableState.value = OperationState(
                phase = OperationPhase.STOPPING,
                physicalRelayState = PhysicalRelayState.UNKNOWN,
                target = snapshot.target,
                generation = generation,
                message = "User-requested safety OFF.",
            )
        }
        return withContext(NonCancellable) {
            try {
                targetReadiness.requireReady(snapshot.target)
                sendCommand(snapshot, isOn = false, token = null)
                markerMutex.withLock { riskMarkerStore.clear() }
                synchronized(gate) {
                    if (generation == offGeneration) {
                        mutableState.value = OperationState(
                            physicalRelayState = PhysicalRelayState.OFF_COMMAND_SENT,
                            target = snapshot.target,
                            generation = generation,
                            message = "OFF command sent.",
                        )
                    }
                }
                OperationResult.Stopped
            } catch (error: Exception) {
                faultIfGeneration(offGeneration, snapshot.target, "Safety OFF failed: ${error.safeMessage()}")
                OperationResult.Failed(error.safeMessage())
            }
        }
    }

    /** Service teardown attempts OFF only when this coordinator actually owns an operation. */
    suspend fun shutdown(): OperationResult = stop()

    private suspend fun startOperation(
        snapshot: RelayConfigurationSnapshot,
        owner: OperationOwner,
        durationMillis: Long?,
        maximumDurationMillis: Long?,
    ): OperationResult {
        try {
            snapshot.validate()
            if (owner == OperationOwner.SINGLE) {
                require(durationMillis != null && maximumDurationMillis != null) { "Exposure duration is missing." }
                require(maximumDurationMillis in 1..MAX_SINGLE_EXPOSURE_MILLIS && durationMillis in 1..maximumDurationMillis) {
                    "Exposure exceeds the configured maximum or is not positive."
                }
            }
        } catch (error: IllegalArgumentException) {
            return OperationResult.Rejected(error.safeMessage())
        }
        val risky = try {
            markerMutex.withLock { riskMarkerStore.isAtRisk() }
        } catch (error: Exception) {
            faultWhenUnowned("Cannot read relay safety marker: ${error.safeMessage()}")
            return OperationResult.Failed(error.safeMessage())
        }
        if (risky) {
            faultWhenUnowned("A previous relay operation may still be energized; send an explicit safety OFF.")
            return OperationResult.Rejected("A previous relay operation needs safety OFF.")
        }

        val op: ActiveOperation
        synchronized(gate) {
            if (active != null || mutableState.value.phase != OperationPhase.IDLE) {
                return OperationResult.Rejected("Relay is owned or faulted.")
            }
            generation++
            op = ActiveOperation(generation, owner, snapshot)
            active = op
            mutableState.value = OperationState(
                phase = OperationPhase.PREFLIGHT,
                owner = owner,
                target = snapshot.target,
                generation = generation,
                message = "Checking selected relay and sending preflight OFF.",
            )
        }

        val startup = scope.async(start = CoroutineStart.LAZY) {
            performStart(op, owner, durationMillis)
        }
        val accepted = synchronized(gate) {
            if (isCurrentLocked(op.token)) {
                startJob = startup
                true
            } else false
        }
        if (!accepted) {
            startup.cancel()
            return OperationResult.Rejected("Operation was stopped or replaced.")
        }
        startup.start()
        return try {
            startup.await()
        } catch (cancelled: CancellationException) {
            if (!isCurrent(op.token)) {
                OperationResult.Rejected("Operation was stopped or replaced.")
            } else if (startup.isCancelled) {
                withContext(NonCancellable) {
                    val offGeneration = beginStop(op, "Operation scope ended; sending safety OFF.")
                    if (offGeneration == null) OperationResult.Rejected("Operation was stopped or replaced.")
                    else settleWithOff(op, offGeneration, "Operation scope ended before startup completed.")
                }
            } else {
                // The caller can disappear; the service-owned startup continues independently.
                throw cancelled
            }
        }
    }

    private suspend fun performStart(
        op: ActiveOperation,
        owner: OperationOwner,
        durationMillis: Long?,
    ): OperationResult {
        val snapshot = op.snapshot
        var onAttempted = false
        try {
            targetReadiness.requireReady(snapshot.target)
            checkCurrent(op.token)
            sendCommand(snapshot, isOn = false, token = op.token)
            synchronized(gate) {
                if (isCurrentLocked(op.token)) {
                    mutableState.value = mutableState.value.copy(physicalRelayState = PhysicalRelayState.OFF_COMMAND_SENT)
                }
            }
            markerMutex.withLock {
                checkCurrent(op.token)
                riskMarkerStore.markAtRisk(snapshot)
            }
            checkCurrent(op.token)
            onAttempted = true
            sendCommand(snapshot, isOn = true, token = op.token)
            checkCurrent(op.token)
            val startedAt = clock.elapsedRealtimeMillis()
            val deadline = if (durationMillis == null) null else Math.addExact(startedAt, durationMillis)
            synchronized(gate) {
                if (isCurrentLocked(op.token)) {
                    mutableState.value = OperationState(
                        phase = if (owner == OperationOwner.MANUAL) OperationPhase.MANUAL_ON else OperationPhase.SINGLE_EXPOSURE,
                        owner = owner,
                        physicalRelayState = PhysicalRelayState.ON_COMMAND_SENT,
                        target = snapshot.target,
                        generation = op.token,
                        startedAtElapsedMillis = startedAt,
                        deadlineElapsedMillis = deadline,
                        message = "ON command sent.",
                    )
                }
            }
            if (owner == OperationOwner.SINGLE) {
                val timer = scope.launch { waitThenClose(op, requireNotNull(deadline)) }
                synchronized(gate) {
                    if (isCurrentLocked(op.token)) singleJob = timer else timer.cancel()
                }
            }
            synchronized(gate) { if (startJob?.isActive == true) startJob = null }
            return OperationResult.Started
        } catch (error: Exception) {
            if (!isCurrent(op.token)) return OperationResult.Rejected("Operation was stopped or replaced.")
            return withContext(NonCancellable) {
                if (onAttempted) {
                    val offGeneration = beginStop(op, "ON or startup failed; sending safety OFF.")
                    if (offGeneration == null) OperationResult.Rejected("Operation was stopped or replaced.")
                    else settleWithOff(op, offGeneration, "Operation failed: ${error.safeMessage()}")
                } else {
                    faultCurrent(op, "Preflight failed before ON: ${error.safeMessage()}")
                    OperationResult.Failed(error.safeMessage())
                }
            }
        }
    }

    private suspend fun waitThenClose(op: ActiveOperation, deadline: Long) {
        try {
            while (isCurrent(op.token)) {
                val remaining = deadline - clock.elapsedRealtimeMillis()
                if (remaining <= 0) break
                sleeper.sleep(remaining)
            }
            val offGeneration = beginStop(op, "Exposure complete; sending OFF.") ?: return
            withContext(NonCancellable) { settleWithOff(op, offGeneration, completionMessage = null) }
        } catch (error: Exception) {
            val offGeneration = beginStop(op, "Exposure timer failed; sending safety OFF.") ?: return
            withContext(NonCancellable) {
                settleWithOff(op, offGeneration, "Exposure timer failed: ${error.safeMessage()}")
            }
        }
    }

    private fun beginStop(op: ActiveOperation, message: String): Long? = synchronized(gate) {
        if (!isCurrentLocked(op.token)) return@synchronized null
        generation++
        mutableState.value = mutableState.value.copy(
            phase = OperationPhase.STOPPING,
            generation = generation,
            message = message,
        )
        generation
    }

    private suspend fun settleWithOff(
        op: ActiveOperation,
        stopGeneration: Long,
        completionMessage: String?,
    ): OperationResult {
        return try {
            sendCommand(op.snapshot, isOn = false, token = null)
            val committed = markerMutex.withLock {
                if (!isStopping(stopGeneration)) false
                else {
                    riskMarkerStore.clear()
                    val stillStopping = synchronized(gate) {
                        if (generation == stopGeneration && mutableState.value.phase == OperationPhase.STOPPING) {
                            active = null
                            startJob = null
                            singleJob = null
                            mutableState.value = OperationState(
                                physicalRelayState = PhysicalRelayState.OFF_COMMAND_SENT,
                                target = op.snapshot.target,
                                generation = generation,
                                message = completionMessage ?: "OFF command sent.",
                            )
                            true
                        } else false
                    }
                    if (!stillStopping) riskMarkerStore.markAtRisk(op.snapshot)
                    stillStopping
                }
            }
            if (!committed) return OperationResult.Rejected("Operation changed before OFF could be recorded.")
            if (completionMessage == null) OperationResult.Stopped else OperationResult.Failed(completionMessage)
        } catch (error: Exception) {
            synchronized(gate) {
                if (generation == stopGeneration) active = null
            }
            faultIfGeneration(stopGeneration, op.snapshot.target, "OFF could not be transmitted: ${error.safeMessage()}")
            OperationResult.Failed(error.safeMessage())
        }
    }

    private suspend fun sendCommand(snapshot: RelayConfigurationSnapshot, isOn: Boolean, token: Long?) {
        val attempts = if (isOn) 2 else 3
        commandMutex.withLock {
            var lastError: Exception? = null
            for (attempt in 1..attempts) {
                if (token != null) checkCurrent(token)
                try {
                    transport.send(snapshot.target, snapshot.serial, snapshot.command(isOn))
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastError = error
                    if (attempt < attempts) {
                        if (token != null) checkCurrent(token)
                        sleeper.sleep(100L * attempt)
                    }
                }
            }
            throw IOException("Relay ${if (isOn) "ON" else "OFF"} failed after $attempts attempts.", lastError)
        }
    }

    private fun faultCurrent(op: ActiveOperation, message: String) = synchronized(gate) {
        if (!isCurrentLocked(op.token)) return@synchronized
        generation++
        active = null
        singleJob = null
        mutableState.value = OperationState(
            phase = OperationPhase.FAULT,
            physicalRelayState = PhysicalRelayState.UNKNOWN,
            target = op.snapshot.target,
            generation = generation,
            message = message,
        )
    }

    private fun faultIfGeneration(expected: Long, target: RelayTarget, message: String) = synchronized(gate) {
        if (generation != expected) return@synchronized
        mutableState.value = OperationState(
            phase = OperationPhase.FAULT,
            physicalRelayState = PhysicalRelayState.UNKNOWN,
            target = target,
            generation = generation,
            message = message,
        )
    }

    private fun faultWhenUnowned(message: String) = synchronized(gate) {
        if (active != null || mutableState.value.phase == OperationPhase.STOPPING) return@synchronized
        generation++
        mutableState.value = OperationState(
            phase = OperationPhase.FAULT,
            physicalRelayState = PhysicalRelayState.UNKNOWN,
            generation = generation,
            message = message,
        )
    }

    private fun isStopping(expectedGeneration: Long): Boolean = synchronized(gate) {
        generation == expectedGeneration && mutableState.value.phase == OperationPhase.STOPPING
    }

    private fun checkCurrent(token: Long) {
        if (!isCurrent(token)) throw StaleOperationException()
    }

    private fun isCurrent(token: Long): Boolean = synchronized(gate) { isCurrentLocked(token) }
    private fun isCurrentLocked(token: Long): Boolean = active?.token == token && generation == token

    private class StaleOperationException : CancellationException("Operation is no longer current.")
}

private fun Throwable.safeMessage(): String = message ?: this::class.java.simpleName
