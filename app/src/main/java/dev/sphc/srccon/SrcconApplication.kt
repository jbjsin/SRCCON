package dev.sphc.srccon

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.sphc.srccon.core.MonotonicClock
import dev.sphc.srccon.core.OperationCoordinator
import dev.sphc.srccon.core.RelayTarget
import dev.sphc.srccon.core.Sleeper
import dev.sphc.srccon.core.TargetReadiness
import dev.sphc.srccon.settings.DataStoreRiskMarkerStore
import dev.sphc.srccon.settings.SrcconSettingsRepository
import dev.sphc.srccon.transport.FakeSerialRelayTransport
import dev.sphc.srccon.transport.SerialRelayTransport
import dev.sphc.srccon.usb.UsbConnectionSession
import dev.sphc.srccon.usb.UsbDeviceRepository
import dev.sphc.srccon.usb.UsbPermissionManager
import dev.sphc.srccon.usb.UsbSerialRelayTransport
import dev.sphc.srccon.usb.key
import dev.sphc.srccon.usb.matchesUsbDetach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Process-level dependency owner. Operations outlive Activity recreation. */
class SrcconApplication : Application() {
    private val applicationJob = SupervisorJob()
    val applicationScope = CoroutineScope(applicationJob + Dispatchers.Default)

    lateinit var settings: SrcconSettingsRepository
        private set
    lateinit var usbDevices: UsbDeviceRepository
        private set
    lateinit var usbSession: UsbConnectionSession
        private set
    lateinit var coordinator: OperationCoordinator
        private set

    val demoTransport = FakeSerialRelayTransport()

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detached = intent.usbDeviceExtra() ?: return
            val key = detached.key()
            val target = currentUsbTarget()
            usbSession.onDetached(key)
            if (target != null && matchesUsbDetach(target, key)) {
                coordinator.onUsbDetached(target)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SrcconSettingsRepository(this)
        usbDevices = UsbDeviceRepository(this)
        usbSession = UsbConnectionSession(
            devices = usbDevices,
            permissions = UsbPermissionManager(this, usbDevices),
        )

        val usbTransport = UsbSerialRelayTransport(this, usbDevices)
        val routingTransport = SerialRelayTransport { target, serial, command ->
            when (target) {
                RelayTarget.Demo -> demoTransport.send(target, serial, command)
                is RelayTarget.Usb -> usbTransport.send(target, serial, command)
            }
        }
        val readiness = TargetReadiness { target ->
            when (target) {
                RelayTarget.Demo -> Unit
                is RelayTarget.Usb -> usbSession.requireReady(target)
            }
        }
        coordinator = OperationCoordinator(
            scope = applicationScope,
            transport = routingTransport,
            targetReadiness = readiness,
            clock = MonotonicClock(SystemClock::elapsedRealtime),
            sleeper = Sleeper { delay(it) },
            riskMarkerStore = DataStoreRiskMarkerStore(settings),
        )

        ContextCompat.registerReceiver(
            this,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        applicationScope.launch { coordinator.restoreRiskMarker() }
    }

    private fun currentUsbTarget(): RelayTarget.Usb? = when (val current = usbSession.state.value) {
        is dev.sphc.srccon.usb.UsbSessionState.Selected -> current.target
        is dev.sphc.srccon.usb.UsbSessionState.PermissionRequested -> current.target
        is dev.sphc.srccon.usb.UsbSessionState.Ready -> current.target
        is dev.sphc.srccon.usb.UsbSessionState.Fault -> current.target
        dev.sphc.srccon.usb.UsbSessionState.Disconnected -> null
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
