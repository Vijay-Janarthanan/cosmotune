package com.cosmotune.music.data.room.voice

import android.Manifest
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cosmotune.music.R
import com.cosmotune.music.data.room.PhoneRooms

/**
 * Keeps the microphone working while the app is in the background, which Android 14 and later only allow from a foreground service
 * of type `microphone` (and Android 11 to 13 cut background capture to silence without one).
 *
 * Not PlaybackService, although it is the app's foreground service: Media3 owns its foreground state, calls startForeground with
 * the mediaPlayback type alone each time it refreshes the notification (which would drop the microphone type again within
 * seconds), and leaves the foreground while the music is paused or before anything has been queued, which is exactly when a room
 * may still be talking. So the microphone has its own small service and notification, up only while this device is sending.
 *
 * Started with startService, not startForegroundService: unmuting is a tap with the app on screen, where a plain start is allowed,
 * and it carries no promise to reach the foreground within seconds, so a refusal (the permission withdrawn meanwhile) simply stops
 * the service instead of crashing the app.
 */
object VoiceForeground {
    fun start(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching { context.startService(Intent(context, VoiceCaptureService::class.java)) != null }
            .onFailure { Log.w(TAG, "microphone service not started", it) }
            .getOrDefault(false)
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, VoiceCaptureService::class.java)) }
    }

    /** The foreground type for the service: `microphone` from Android 11, where it exists; nothing before (any type kept the microphone then). */
    fun serviceType(sdk: Int = Build.VERSION.SDK_INT): Int = if (sdk >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    internal const val TAG = "CosmoVoice"
    internal const val CHANNEL_ID = "room_voice"
    internal const val NOTIFICATION_ID = 0x766F

    internal fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.voice_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }
}

class VoiceCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching {
            VoiceForeground.channel(this)
            val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val notification = NotificationCompat.Builder(this, VoiceForeground.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(getString(R.string.voice_notification_title))
                .setContentText(getString(R.string.voice_notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
            ServiceCompat.startForeground(this, VoiceForeground.NOTIFICATION_ID, notification, VoiceForeground.serviceType())
        }.onFailure { Log.w(VoiceForeground.TAG, "microphone service refused the foreground", it) }.isSuccess
        // without the foreground the voice still works on screen; capture may only stop in the background
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Swiped away from the recent apps while sending. The process may live on for the music, and with it the capture: the
     * microphone is turned off (the room hears it go muted) and the service goes, so nothing records with no screen to say so.
     * When the process itself dies the capture dies with it, and START_NOT_STICKY keeps this from coming back on its own.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        PhoneRooms.muteForTaskRemoved()
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }
}
