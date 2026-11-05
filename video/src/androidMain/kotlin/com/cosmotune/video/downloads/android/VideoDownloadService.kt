package com.cosmotune.video.downloads.android

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The foreground service (type `dataSync`) that keeps downloads going when the screen is left (plan task Y-24, step 5),
 * declared in this module's manifest and not exported. It does no work itself: [AndroidDownloadController] runs the queue
 * and calls [show] while something downloads (the notification: "Downloading <title>" with its progress and a Pause action,
 * on the channel "Video downloads") and [stop] when the queue is idle or held. Pause reaches the controller through
 * [AndroidDownloadController.pauseActive]. On Android 12 and later a start from the background may be refused: the download
 * then goes on without the notification while the app lives.
 */
class VideoDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            AndroidDownloadController.current?.pauseActive()
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = pending
        if (notification == null) {
            // started, then asked to stop before it ran: Android still wants the foreground call first
            ServiceCompat.startForeground(this, NOTIFICATION_ID, placeholder(this), foregroundType())
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType())
        running = true
        starting = false
        if (stopRequested) {
            stopRequested = false
            stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15's limit for `dataSync` services: leave the foreground; the queue goes on while the app lives. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        running = false
        starting = false
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        running = false
        starting = false
        stopSelf()
    }

    companion object {
        /** The notification channel's id; its name is `video_dl_channel` ("Video downloads"). */
        const val CHANNEL_ID = "cosmotune_video_downloads"

        /** The notification's id. */
        const val NOTIFICATION_ID = 0x7D24

        private const val ACTION_PAUSE = "com.cosmotune.video.downloads.action.PAUSE"

        @Volatile private var running = false
        @Volatile private var starting = false
        @Volatile private var stopRequested = false
        @Volatile private var pending: Notification? = null

        /**
         * Shows "[title]" with [percent] (null while joining: an indeterminate bar) and a [pauseLabel] action on the channel
         * [channelName], starting the service in the foreground when it is not running.
         */
        fun show(context: Context, channelName: String, title: String, percent: Int?, pauseLabel: String) {
            val notification = build(context, channelName, title, percent, pauseLabel)
            pending = notification
            stopRequested = false
            when {
                running -> notify(context, notification)
                !starting -> try {
                    starting = true
                    ContextCompat.startForegroundService(context, Intent(context, VideoDownloadService::class.java))
                } catch (_: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException (Android 12+, started from the background)
                    starting = false
                }
            }
        }

        /** Leaves the foreground and stops the service (the notification goes with it). */
        fun stop(context: Context) {
            pending = null
            when {
                running -> context.stopService(Intent(context, VideoDownloadService::class.java))
                starting -> stopRequested = true
            }
        }

        private fun foregroundType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        private fun channel(context: Context, name: String) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }

        private fun build(context: Context, channelName: String, title: String, percent: Int?, pauseLabel: String): Notification {
            channel(context, channelName)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, 0, it, flags) }
            val pause = PendingIntent.getService(context, 1, Intent(context, VideoDownloadService::class.java).setAction(ACTION_PAUSE), flags)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setProgress(100, percent ?: 0, percent == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause, pauseLabel, pause)
                .build()
        }

        private fun placeholder(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setSilent(true)
            .build()

        @SuppressLint("MissingPermission")
        private fun notify(context: Context, notification: Notification) {
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!allowed) return
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }
    }
}
