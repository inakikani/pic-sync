package com.inakikani.picsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import com.inakikani.picsync.data.AppLog
import com.inakikani.picsync.data.UploadStatus
import com.inakikani.picsync.data.UploadTasks
import com.inakikani.picsync.data.summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Keeps uploads going when pic-sync isn't on screen. The uploads run in their view models
 * (gallery, share screen); while any is registered in [tasks], this foreground service
 * (type dataSync) keeps the app in the foreground state, shows their progress with Cancel,
 * and holds a partial wake lock so they also continue with the screen off.
 */
class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    // Built once: the notification is redrawn up to once a second.
    private val openApp by lazy {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
    }
    private val cancelAction by lazy {
        val intent = Intent(this, UploadService::class.java).setAction(ACTION_CANCEL)
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_close),
            "Cancel",
            PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE),
        ).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of uploads to the Mac"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            AppLog.i("background: cancel tapped in the notification")
            tasks.cancelAll()
            if (!watching) stopSelf(startId) // a stale notification: nothing was running
            return START_NOT_STICKY
        }
        lastStartId = startId
        // Required promptly after every startForegroundService, even if the upload already ended.
        startForeground(NOTIFICATION_ID, build(summary(tasks.tasks.value.values)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!watching) {
            watching = true
            AppLog.i("background: upload service started")
            // Released in onDestroy; the timeout is a safety net (dataSync services are
            // limited to 6 hours a day on Android 15+ anyway).
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pic-sync:upload")
                .apply { acquire(TimeUnit.HOURS.toMillis(6)) }
            scope.launch { watch() }
        }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val nm = getSystemService(NotificationManager::class.java)
        // A StateFlow: while this waits, intermediate updates are dropped.
        tasks.tasks.collect { current ->
            if (current.isEmpty()) {
                // Stops only if no new upload has started the service again meanwhile.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
            } else {
                nm.notify(NOTIFICATION_ID, build(summary(current.values)))
                delay(1_000) // Android drops notification updates sent faster than this
            }
        }
    }

    private fun build(status: UploadStatus): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(status.title)
            .setContentText(status.text)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, ((status.progress ?: 0f) * 1000).toInt(), status.progress == null)
            .setContentIntent(openApp)
            .addAction(cancelAction)
            .build()

    /** Android 15+: the daily dataSync time is used up. Uploads go on only while the app is open. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w("background: Android stopped the upload service (time limit); uploads continue only while pic-sync is open")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        AppLog.i("background: upload service stopped")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "uploads"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "com.inakikani.picsync.CANCEL_UPLOADS"

        /** The uploads in progress, shown by this service. */
        val tasks = UploadTasks()

        /**
         * Registers an upload and starts the service. Call when the upload starts, while the
         * app is on screen (Android only allows starting the service then). [cancel] runs if
         * the user taps Cancel in the notification. End the returned task when the upload stops.
         */
        fun begin(ctx: Context, title: String, cancel: () -> Unit): UploadTasks.Task {
            val task = tasks.begin(title, cancel)
            val app = ctx.applicationContext
            try {
                app.startForegroundService(Intent(app, UploadService::class.java))
            } catch (e: Exception) { // e.g. ForegroundServiceStartNotAllowedException
                AppLog.w("background: can't start the upload service; uploads only continue while pic-sync is open", e)
            }
            return task
        }
    }
}
