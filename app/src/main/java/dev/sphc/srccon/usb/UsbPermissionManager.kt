package dev.sphc.srccon.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.sphc.srccon.core.RelayTarget
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UsbPermissionResult { GRANTED, DENIED, DEVICE_MISSING }

/** Call only from a user-initiated Connect action. Scan never calls this class. */
class UsbPermissionManager(
    context: Context,
    private val devices: UsbDeviceRepository,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val requestMutex = Mutex()

    suspend fun requestForExplicitConnect(target: RelayTarget.Usb): UsbPermissionResult =
        requestMutex.withLock {
            val device = devices.findCurrent(target) ?: return@withLock UsbPermissionResult.DEVICE_MISSING
            if (manager.hasPermission(device)) return@withLock UsbPermissionResult.GRANTED
            val granted = awaitUserDecision(device)
            val current = devices.findCurrent(target)
            when {
                current == null -> UsbPermissionResult.DEVICE_MISSING
                granted && manager.hasPermission(current) -> UsbPermissionResult.GRANTED
                else -> UsbPermissionResult.DENIED
            }
        }

    private suspend fun awaitUserDecision(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { continuation ->
            val requestCode = nextRequestCode.getAndIncrement()
            val action = "${appContext.packageName}.USB_PERMISSION.$requestCode"
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                requestCode,
                Intent(action).setPackage(appContext.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
            val finished = AtomicBoolean(false)
            lateinit var receiver: BroadcastReceiver

            fun finish(granted: Boolean) {
                if (!finished.compareAndSet(false, true)) return
                runCatching { appContext.unregisterReceiver(receiver) }
                pendingIntent.cancel()
                if (continuation.isActive) continuation.resume(granted)
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != action) return
                    val returned = intent.usbDeviceExtra()
                    finish(returned?.key() == device.key() &&
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }

            try {
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                continuation.invokeOnCancellation { finish(false) }
                manager.requestPermission(device, pendingIntent)
            } catch (error: Exception) {
                finish(false)
            }
        }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        val nextRequestCode = AtomicInteger(1)
    }
}
