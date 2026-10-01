package dev.sphc.srccon.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sphc.srccon.R
import dev.sphc.srccon.SrcconApplication
import dev.sphc.srccon.core.BuiltInRelayProfiles
import dev.sphc.srccon.core.OperationPhase
import dev.sphc.srccon.core.OperationResult
import dev.sphc.srccon.core.OperationState
import dev.sphc.srccon.core.RelayTarget
import dev.sphc.srccon.service.ShutterForegroundService
import dev.sphc.srccon.settings.AppSettings
import dev.sphc.srccon.usb.UsbDeviceDescriptor
import dev.sphc.srccon.usb.UsbDeviceFingerprint
import dev.sphc.srccon.usb.UsbDeviceKey
import dev.sphc.srccon.usb.UsbSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SrcconUiState(
    val settings: AppSettings = AppSettings(),
    val devices: List<UsbDeviceDescriptor> = emptyList(),
    val selectedKey: UsbDeviceKey? = null,
    val usbSession: UsbSessionState = UsbSessionState.Disconnected,
    val demoReady: Boolean = false,
    val operation: OperationState = OperationState(),
    val notificationPermissionGranted: Boolean = true,
    val message: String? = null,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
) {
    val readyTarget: RelayTarget?
        get() = when {
            demoReady -> RelayTarget.Demo
            usbSession is UsbSessionState.Ready -> usbSession.target
            else -> null
        }

    val canChangeConnection: Boolean
        get() = operation.phase == OperationPhase.IDLE || operation.phase == OperationPhase.FAULT
}

class SrcconViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SrcconApplication
    private val mutableUiState = MutableStateFlow(
        SrcconUiState(notificationPermissionGranted = notificationPermissionGranted()),
    )
    val uiState: StateFlow<SrcconUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            app.settings.settings.collect { settings ->
                mutableUiState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            app.usbSession.state.collect { connection ->
                mutableUiState.update { state -> state.copy(usbSession = connection) }
            }
        }
        viewModelScope.launch {
            app.coordinator.state.collect { operation ->
                mutableUiState.update { it.copy(operation = operation) }
            }
        }
        scan()
    }

    fun refreshNotificationPermission() {
        mutableUiState.update { it.copy(notificationPermissionGranted = notificationPermissionGranted()) }
    }

    fun scan() {
        if (mutableUiState.value.scanning) return
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(scanning = true, message = app.getString(R.string.message_scanning))
            }
            val result = runCatching { app.usbDevices.scan().filter(UsbDeviceDescriptor::supported) }
            mutableUiState.update { state ->
                if (result.isSuccess) {
                    val devices = result.getOrThrow()
                    state.copy(
                        devices = devices,
                        scanning = false,
                        selectedKey = state.selectedKey?.takeIf { selected -> devices.any { it.key == selected } },
                        message = app.getString(R.string.message_scan_complete, devices.size),
                    )
                } else {
                    state.copy(scanning = false, message = result.exceptionOrNull()?.safeMessage())
                }
            }
        }
    }

    fun selectDevice(device: UsbDeviceDescriptor) {
        if (!mutableUiState.value.canChangeConnection) return
        val next = app.usbSession.selectFromUser(device)
        mutableUiState.update {
            it.copy(
                selectedKey = device.key,
                demoReady = false,
                usbSession = next,
                message = null,
            )
        }
    }

    fun connect() {
        if (mutableUiState.value.connecting || !mutableUiState.value.canChangeConnection) return
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(connecting = true, message = app.getString(R.string.message_requesting_permission))
            }
            val next = try {
                app.usbSession.connectFromUser()
            } catch (error: Exception) {
                mutableUiState.update { it.copy(connecting = false, message = error.safeMessage()) }
                return@launch
            }
            if (next is UsbSessionState.Ready) {
                runCatching {
                    app.settings.setLastDeviceFingerprint(next.device.fingerprintHint().encode())
                }
            }
            mutableUiState.update {
                it.copy(
                    connecting = false,
                    usbSession = next,
                    demoReady = false,
                    message = when (next) {
                        is UsbSessionState.Ready -> app.getString(
                            R.string.message_connected,
                            next.device.productName ?: next.device.key.deviceName,
                        )
                        is UsbSessionState.Fault -> next.message
                        else -> null
                    },
                )
            }
        }
    }

    fun startDemo() {
        val state = mutableUiState.value
        if (state.operation.phase != OperationPhase.IDLE || state.settings.mayBeEnergized) {
            showMessage(state.operation.message ?: app.getString(R.string.status_fault))
            return
        }
        app.usbSession.clearSelection()
        mutableUiState.update {
            it.copy(
                selectedKey = null,
                demoReady = true,
                message = app.getString(R.string.message_demo_ready),
            )
        }
    }

    fun disconnect() {
        val state = mutableUiState.value
        if (!state.canChangeConnection) {
            showMessage(app.getString(R.string.message_busy))
            return
        }
        app.usbSession.clearSelection()
        mutableUiState.update {
            it.copy(
                selectedKey = null,
                demoReady = false,
                message = app.getString(R.string.message_disconnected),
            )
        }
    }

    fun validateProfile() {
        val target = mutableUiState.value.readyTarget ?: RelayTarget.Demo
        val result = runCatching { BuiltInRelayProfiles.vctecNoyito.snapshotFor(target) }
        showMessage(
            if (result.isSuccess) app.getString(R.string.message_profile_valid)
            else result.exceptionOrNull()?.safeMessage().orEmpty(),
        )
    }

    fun startManual() = launchOperation { target ->
        app.coordinator.startManual(BuiltInRelayProfiles.vctecNoyito.snapshotFor(target))
    }

    fun startSingle(seconds: Int) {
        val maximum = mutableUiState.value.settings.maximumExposureSeconds
        if (seconds !in 1..maximum) {
            showMessage(app.getString(R.string.message_invalid_duration, maximum))
            return
        }
        viewModelScope.launch { runCatching { app.settings.setSingleExposureSeconds(seconds) } }
        launchOperation { target ->
            app.coordinator.startSingle(
                snapshot = BuiltInRelayProfiles.vctecNoyito.snapshotFor(target),
                durationMillis = seconds * 1_000L,
                maximumDurationMillis = maximum * 1_000L,
            )
        }
    }

    fun stop() {
        val physical = app.coordinator.state.value.target is RelayTarget.Usb
        viewModelScope.launch { reportResult(app.coordinator.stop(), physicalServiceWasArmed = physical) }
    }

    fun manualOff() {
        val physical = app.coordinator.state.value.target is RelayTarget.Usb
        viewModelScope.launch { reportResult(app.coordinator.manualOff(), physicalServiceWasArmed = physical) }
    }

    fun safetyOff() {
        val state = mutableUiState.value
        if (state.settings.mayBeEnergized && state.readyTarget !is RelayTarget.Usb) {
            showMessage(app.getString(R.string.fault_recovery_note))
            return
        }
        launchOperation(allowFault = true) { target ->
            app.coordinator.safetyOff(BuiltInRelayProfiles.vctecNoyito.snapshotFor(target))
        }
    }

    fun cycleTheme() {
        viewModelScope.launch { app.settings.setTheme(mutableUiState.value.settings.theme.next()) }
    }

    fun setTheme(mode: AppThemeMode) {
        viewModelScope.launch { app.settings.setTheme(mode) }
    }

    fun saveMaximumExposure(seconds: Int) {
        viewModelScope.launch {
            val result = runCatching { app.settings.setMaximumExposureSeconds(seconds) }
            showMessage(
                if (result.isSuccess) app.getString(R.string.message_max_saved, seconds)
                else result.exceptionOrNull()?.safeMessage().orEmpty(),
            )
        }
    }

    fun clearMessage() = mutableUiState.update { it.copy(message = null) }

    fun previousFingerprint(): UsbDeviceFingerprint? =
        UsbDeviceFingerprint.decode(mutableUiState.value.settings.lastDeviceFingerprint)

    private fun launchOperation(
        allowFault: Boolean = false,
        action: suspend (RelayTarget) -> OperationResult,
    ) {
        val state = mutableUiState.value
        val target = state.readyTarget
        if (target == null) {
            showMessage(app.getString(R.string.connect_to_control))
            return
        }
        if (!allowFault && state.operation.phase == OperationPhase.FAULT) {
            showMessage(state.operation.message ?: app.getString(R.string.status_fault))
            return
        }
        val physical = target is RelayTarget.Usb
        if (physical && !state.notificationPermissionGranted) {
            showMessage(app.getString(R.string.message_notifications_required))
            return
        }
        if (physical) {
            val armed = runCatching { ShutterForegroundService.arm(app) }
            if (armed.isFailure) {
                showMessage(armed.exceptionOrNull()?.safeMessage().orEmpty())
                return
            }
        }
        viewModelScope.launch {
            val result = try {
                action(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OperationResult.Failed(error.safeMessage())
            }
            reportResult(result, physical)
        }
    }

    private fun reportResult(result: OperationResult, physicalServiceWasArmed: Boolean) {
        val message = when (result) {
            OperationResult.Started -> null
            OperationResult.Stopped -> app.getString(R.string.relay_off_sent)
            is OperationResult.Rejected -> result.reason
            is OperationResult.Failed -> app.getString(R.string.message_operation_failed, result.reason)
        }
        val shouldDisarm = result is OperationResult.Rejected ||
            app.coordinator.state.value.phase == OperationPhase.IDLE
        if (physicalServiceWasArmed && shouldDisarm) {
            ShutterForegroundService.disarm(app)
        }
        if (message != null) showMessage(message)
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun showMessage(message: String) {
        mutableUiState.update { it.copy(message = message) }
    }
}

private fun Throwable.safeMessage(): String = message ?: this::class.java.simpleName
