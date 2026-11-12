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
            confirm ?: return
            // Android blocks a screen opened from the background; then a notification opens it instead.
            if (AppUpdateChecker.foreground.inFront && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
            updateNotification(context, "Cosmotune update ready", "Tap to install", confirm, CONFIRM_ID)
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
        val pm = context.packageManager
        val launch = (pm.getLeanbackLaunchIntentForPackage(context.packageName) ?: pm.getLaunchIntentForPackage(context.packageName)) ?: return
        updateNotification(context, "Cosmotune updated", "Tap to open", launch, UPDATED_ID)
    }
}

private const val CHANNEL = "updates"
private const val UPDATED_ID = 7_023
private const val CONFIRM_ID = 7_024

/** A notification on the Updates channel that opens [target]; nothing when notifications are off. */
private fun updateNotification(context: Context, title: String, text: String, target: Intent, id: Int) {
    val notifications = NotificationManagerCompat.from(context)
    if (!notifications.areNotificationsEnabled()) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val open = PendingIntent.getActivity(context, id, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_logo)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    runCatching { notifications.notify(id, notification) }
}
