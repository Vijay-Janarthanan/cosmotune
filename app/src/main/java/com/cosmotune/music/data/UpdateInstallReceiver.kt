package com.cosmotune.music.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cosmotune.music.R

/**
 * The result of the update's [PackageInstaller] session ([AppUpdateChecker.installApk]): when Android wants the person to confirm,
 * its "Install?" screen is opened; a failure goes back to the update card. Success needs nothing here: the app is being replaced.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        AppUpdateChecker.installStatusFailure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            ?.let(AppUpdateChecker::reportInstallFailure)
    }
}

/**
 * Android does not reopen an app it has just updated; when it is allowed to, Cosmotune leaves a notification that does
 * ("Cosmotune updated · Tap to open").
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        val pm = context.packageManager
        val launch = (pm.getLeanbackLaunchIntentForPackage(context.packageName) ?: pm.getLaunchIntentForPackage(context.packageName)) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle("Cosmotune updated")
            .setContentText("Tap to open")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 7_023
    }
}
