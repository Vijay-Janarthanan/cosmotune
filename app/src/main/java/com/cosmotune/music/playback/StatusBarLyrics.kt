package com.cosmotune.music.playback

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cosmotune.music.R

/**
 * Status bar lyrics (Settings > Lyrics > Lyrics in the status bar): the line being sung, as the ticker of a quiet notification
 * that carries Flyme's status bar lyric flags. Meizu phones show it in the status bar themselves; on other phones a status bar
 * lyrics module that reads those flags (Lyric Getter, StatusBarLyric) shows it. Idea from Gramophone; the code is Cosmotune's.
 */
internal class StatusBarLyrics(private val context: Context) {
    private var shown: String? = null

    /** Shows [line] (null or blank takes the lyric away). Called from the service's lyric tick, on the main thread. */
    fun show(line: String?) {
        val text = line?.trim()?.takeIf { it.isNotEmpty() }
        if (text == shown) return
        shown = text
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (text == null) {
            manager.cancel(NOTIFICATION)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lyrics in the status bar", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            },
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(text)
            .setTicker(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        notification.flags = notification.flags or FLAG_ALWAYS_SHOW_TICKER or FLAG_ONLY_UPDATE_TICKER
        runCatching { manager.notify(NOTIFICATION, notification) }
    }

    fun clear() = show(null)

    private companion object {
        const val CHANNEL = "status_bar_lyrics"
        const val NOTIFICATION = 8233
        /** Flyme's flags: keep the ticker showing, and only the ticker changes between lines. */
        const val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
        const val FLAG_ONLY_UPDATE_TICKER = 0x02000000
    }
}
