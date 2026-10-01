package dev.sphc.srccon.usb

import dev.sphc.srccon.core.RelayTarget
import dev.sphc.srccon.core.TargetReadiness
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface UsbSessionState {
    data object Disconnected : UsbSessionState
    data class Selected(val target: RelayTarget.Usb, val device: UsbDeviceDescriptor) : UsbSessionState
    data class PermissionRequested(val target: RelayTarget.Usb, val device: UsbDeviceDescriptor) : UsbSessionState
    /** Logical readiness only. No serial interface is held open. */
    data class Ready(val target: RelayTarget.Usb, val device: UsbDeviceDescriptor) : UsbSessionState
    data class Fault(val target: RelayTarget.Usb?, val message: String) : UsbSessionState
}

/** Selection and Connect are user actions. Neither scan nor selection requests permission or opens USB. */
class UsbConnectionSession(
    private val devices: UsbDeviceRepository,
    private val permissions: UsbPermissionManager,
) : TargetReadiness {
    private val lock = Any()
    private var generation = 0L
    private val mutableState = MutableStateFlow<UsbSessionState>(UsbSessionState.Disconnected)
    val state: StateFlow<UsbSessionState> = mutableState.asStateFlow()

    fun selectFromUser(device: UsbDeviceDescriptor, portIndex: Int = 0): UsbSessionState {
        val current = devices.findCurrent(device.key)?.let(devices::describe)
            ?: return setFault(null, "Selected USB device is no longer attached")
        if (!current.supported || portIndex !in 0 until current.portCount) {
            return setFault(null, "Selected USB device has no supported serial port")
        }
        return synchronized(lock) {
            generation++
            UsbSessionState.Selected(current.targetForPort(portIndex), current).also { mutableState.value = it }
        }
    }

    suspend fun connectFromUser(): UsbSessionState {
        val request = synchronized(lock) {
            when (val current = mutableState.value) {
                is UsbSessionState.Selected -> {
                    val token = generation
                    mutableState.value = UsbSessionState.PermissionRequested(current.target, current.device)
                    token to current.target
                }
                is UsbSessionState.Ready -> return current
                else -> throw IllegalStateException("Select a USB device before pressing Connect")
            }
        }
        val (token, target) = request
        val result = runCatching { permissions.requestForExplicitConnect(target) }
        return synchronized(lock) {
            if (generation != token) return@synchronized mutableState.value
            val next = when {
                result.isFailure -> UsbSessionState.Fault(
                    target,
                    result.exceptionOrNull()?.message ?: "USB permission request failed",
                )
                result.getOrNull() == UsbPermissionResult.DEVICE_MISSING ->
                    UsbSessionState.Fault(target, "Selected USB device was detached")
                result.getOrNull() == UsbPermissionResult.DENIED ->
                    UsbSessionState.Fault(target, "USB permission was denied")
                else -> {
                    val current = devices.describeCurrent(target)
                    if (current == null || !current.permissionGranted ||
                        !current.supported || target.portIndex !in 0 until current.portCount
                    ) {
                        UsbSessionState.Fault(target, "Selected USB device is no longer ready")
                    } else {
                        UsbSessionState.Ready(target, current)
                    }
                }
            }
            mutableState.value = next
            next
        }
    }

    /** The caller must first stop or finish any operation that still uses this target. */
    fun clearSelection(): UsbSessionState = synchronized(lock) {
        generation++
        UsbSessionState.Disconnected.also { mutableState.value = it }
    }

    fun onDetached(detached: UsbDeviceKey): UsbSessionState = synchronized(lock) {
        val target = when (val current = mutableState.value) {
            is UsbSessionState.Selected -> current.target
            is UsbSessionState.PermissionRequested -> current.target
            is UsbSessionState.Ready -> current.target
            is UsbSessionState.Fault -> current.target
            UsbSessionState.Disconnected -> null
        }
        if (target != null && matchesUsbDetach(target, detached)) {
            generation++
            UsbSessionState.Fault(target, "USB device detached; relay state is unknown")
                .also { mutableState.value = it }
        } else {
            mutableState.value
        }
    }

    override suspend fun requireReady(target: RelayTarget) {
        require(target is RelayTarget.Usb) { "USB session cannot authorize a demo target" }
        val current = synchronized(lock) { mutableState.value }
        if (current !is UsbSessionState.Ready || current.target != target) {
            throw IOException("Explicitly select and connect this USB device first")
        }
        val latest = devices.describeCurrent(target)
        if (latest == null || !latest.permissionGranted || !latest.supported ||
            target.portIndex !in 0 until latest.portCount
        ) {
            synchronized(lock) {
                val selected = mutableState.value as? UsbSessionState.Ready
                if (selected?.target == target) {
                    generation++
                    mutableState.value = UsbSessionState.Fault(
                        target,
                        "Selected USB device is unavailable or permission was lost",
                    )
                }
            }
            throw IOException("Selected USB device is unavailable or permission was lost")
        }
    }

    private fun setFault(target: RelayTarget.Usb?, message: String): UsbSessionState =
        synchronized(lock) {
            generation++
            UsbSessionState.Fault(target, message).also { mutableState.value = it }
        }
}
