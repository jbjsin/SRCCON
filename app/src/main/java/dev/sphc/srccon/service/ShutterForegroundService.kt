package dev.sphc.srccon.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.sphc.srccon.MainActivity
import dev.sphc.srccon.R
import dev.sphc.srccon.SrcconApplication
import dev.sphc.srccon.core.OperationPhase
import dev.sphc.srccon.core.OperationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps physical USB operations visible and CPU timing available while the UI is absent. */
class ShutterForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: SrcconApplication
    private lateinit var wakeLock: PowerManager.WakeLock
    private var armed = false
    private var armedAtGeneration = 0L
    private var operationSeen = false
    private var renderJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as SrcconApplication
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SRCCON:relay-operation")
            .apply { setReferenceCounted(false) }
        createNotificationChannel()
        serviceScope.launch {
            app.coordinator.state.collect(::onOperationState)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch { app.coordinator.stop() }
            ACTION_DISARM -> {
                armed = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                armed = true
                armedAtGeneration = app.coordinator.state.value.generation
                startInForeground(notificationFor(app.coordinator.state.value))
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        renderJob?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        if (app.coordinator.state.value.phase in ACTIVE_PHASES) {
            app.applicationScope.launch { app.coordinator.shutdown() }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onOperationState(state: OperationState) {
        if (!armed) return
        renderJob?.cancel()
        when (state.phase) {
            OperationPhase.PREFLIGHT,
            OperationPhase.MANUAL_ON,
            OperationPhase.SINGLE_EXPOSURE,
            OperationPhase.STOPPING,
            -> {
                operationSeen = true
                holdWakeLock()
                startInForeground(notificationFor(state))
                if (state.phase == OperationPhase.SINGLE_EXPOSURE) {
                    renderJob = serviceScope.launch {
                        while (isActive && app.coordinator.state.value.phase == OperationPhase.SINGLE_EXPOSURE) {
                            postNotification(notificationFor(app.coordinator.state.value))
                            delay(1_000)
                        }
                    }
                }
            }
            OperationPhase.FAULT -> {
                // Ignore a pre-existing crash marker while an explicit Safety OFF
                // service start is waiting to enter STOPPING.
                if (!operationSeen && state.generation == armedAtGeneration) return
                operationSeen = true
                releaseWakeLock()
                val notification = notificationFor(state)
                postNotification(notification)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
            OperationPhase.IDLE -> if (operationSeen) {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationFor(state: OperationState): Notification {
        val content = when (state.phase) {
            OperationPhase.PREFLIGHT -> getString(R.string.notification_preparing)
            OperationPhase.MANUAL_ON -> getString(R.string.notification_manual)
            OperationPhase.SINGLE_EXPOSURE -> {
                val remaining = ((state.deadlineElapsedMillis ?: SystemClock.elapsedRealtime()) -
                    SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1_000.0
                getString(R.string.notification_exposure, remaining)
            }
            OperationPhase.STOPPING -> getString(R.string.notification_stopping)
            OperationPhase.FAULT -> getString(R.string.notification_fault)
            OperationPhase.IDLE -> getString(R.string.notification_preparing)
        }
        val active = state.phase in ACTIVE_PHASES
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (active) {
            val stopIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, ShutterForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_notification, getString(R.string.notification_stop), stopIntent)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_permission_note)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun postNotification(notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun holdWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        private const val CHANNEL_ID = "shutter_operations"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_ARM = "dev.sphc.srccon.action.ARM"
        private const val ACTION_STOP = "dev.sphc.srccon.action.STOP"
        private const val ACTION_DISARM = "dev.sphc.srccon.action.DISARM"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 86_700_000L
        private val ACTIVE_PHASES = setOf(
            OperationPhase.PREFLIGHT,
            OperationPhase.MANUAL_ON,
            OperationPhase.SINGLE_EXPOSURE,
            OperationPhase.STOPPING,
        )

        fun arm(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ShutterForegroundService::class.java).setAction(ACTION_ARM),
            )
        }

        fun disarm(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ShutterForegroundService::class.java).setAction(ACTION_DISARM),
                )
            }
        }
    }
}
