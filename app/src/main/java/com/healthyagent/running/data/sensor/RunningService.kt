package com.healthyagent.running.data.sensor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.healthyagent.running.MainActivity
import com.healthyagent.running.coordinator.AppCoordinator
import com.healthyagent.running.coordinator.AppSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

/**
 * 跑步前台服务 — 保障跑步期间应用不被系统杀死，熄屏不断链
 */
class RunningService : Service(), KoinComponent {

    private val coordinator: AppCoordinator by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false

    companion object {
        const val CHANNEL_ID = "running_channel"
        const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.healthyagent.running.action.START"
        private const val ACTION_PAUSE = "com.healthyagent.running.action.PAUSE"
        private const val ACTION_RESUME = "com.healthyagent.running.action.RESUME"
        private const val ACTION_STOP = "com.healthyagent.running.action.STOP"
        private const val ACTION_RECOVER = "com.healthyagent.running.action.RECOVER"

        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RunningService::class.java).setAction(ACTION_START),
            )
        }

        fun pause(context: android.content.Context) {
            context.startService(Intent(context, RunningService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: android.content.Context) {
            context.startService(Intent(context, RunningService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, RunningService::class.java).setAction(ACTION_STOP))
        }

        fun recover(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RunningService::class.java).setAction(ACTION_RECOVER),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeNotificationState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!ensureForeground()) return START_NOT_STICKY
                if (coordinator.startRun() is com.healthyagent.running.coordinator.CommandOutcome.Rejected) {
                    if (!coordinator.snapshot.value.shouldKeepForeground()) stopServiceNow()
                    return START_NOT_STICKY
                }
            }
            ACTION_PAUSE -> coordinator.pauseRun()
            ACTION_RESUME -> {
                if (!ensureForeground()) return START_NOT_STICKY
                coordinator.resumeRun()
            }
            ACTION_STOP -> {
                val finishingSessionId = coordinator.snapshot.value.session.id
                val outcome = coordinator.stopRun()
                if (outcome is com.healthyagent.running.coordinator.CommandOutcome.Accepted) {
                    stopWhenCompletionSettles(finishingSessionId)
                } else if (!coordinator.snapshot.value.shouldKeepForeground()) {
                    stopServiceNow()
                }
                return START_NOT_STICKY
            }
            ACTION_RECOVER, null -> {
                if (!ensureForeground()) return START_NOT_STICKY
                serviceScope.launch {
                    val restored = coordinator.awaitRestoration()
                    val active = coordinator.snapshot.value.session.state in setOf("running", "paused")
                    if (!restored && !active) {
                        stopServiceNow()
                    } else if (coordinator.snapshot.value.session.state == "running") {
                        coordinator.attachRecoveredRun()
                    }
                }
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) return true
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (hasBleForegroundPermission()) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            types
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(coordinator.snapshot.value.runningNotificationContent()),
                foregroundType,
            )
            foregroundStarted = true
            true
        } catch (_: SecurityException) {
            stopSelf()
            false
        }
    }

    private fun hasBleForegroundPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun stopServiceNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    /** 保持前台服务直到本地保存完成；若期间已开始新跑，则沿用当前服务。 */
    private fun stopWhenCompletionSettles(finishingSessionId: String?) {
        serviceScope.launch {
            coordinator.snapshot.first { snapshot ->
                snapshot.session.id != finishingSessionId || snapshot.persistenceState in setOf("saved", "failed")
            }
            val current = coordinator.snapshot.value
            if (current.session.id == finishingSessionId && current.session.state == "finished") {
                stopServiceNow()
            }
        }
    }

    private fun observeNotificationState() {
        serviceScope.launch {
            coordinator.snapshot
                .map(AppSnapshot::runningNotificationContent)
                .distinctUntilChanged()
                .collect { content ->
                    if (!foregroundStarted) return@collect
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        buildNotification(content),
                    )
                }
        }
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("流萤跑步伴侣")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "跑步状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示跑步实时状态"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

private fun com.healthyagent.running.coordinator.AppSnapshot.shouldKeepForeground(): Boolean {
    return session.state in setOf("running", "paused") || persistenceState == "saving"
}

internal fun AppSnapshot.runningNotificationContent(): String {
    val distanceText = String.format(
        java.util.Locale.US,
        "%.2f km",
        metrics.distanceMeters / 1000.0,
    )
    val elapsedText = "%02d:%02d".format(
        metrics.elapsedSeconds / 60,
        metrics.elapsedSeconds % 60,
    )
    return when {
        persistenceState == "saving" -> "正在保存跑步记录 · $distanceText"
        session.state == "paused" -> "已暂停 · $distanceText · $elapsedText"
        session.state == "finished" -> "本次跑步已结束 · $distanceText"
        session.state == "running" -> "跑步进行中 · $distanceText · $elapsedText"
        else -> "正在准备跑步数据"
    }
}
