package com.cosmotune.podio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.cosmotune.music.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The radio alarm on the phone (Settings › Podio › Radio alarm). [sync] sets the system alarm for the next time ([PodioAlarms.next]);
 * when it goes off this receiver plays the station, softly at first when chosen, and sets the next one. It is set again after a
 * restart of the phone or an update of the app, which clear the system's alarms.
 */
class PodioAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_RING -> {
                val pending = goAsync()
                scope.launch {
                    try { ring(app) } finally { runCatching { pending.finish() } }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> sync(app)
        }
    }

    private suspend fun ring(app: Context) {
        val library = PodioLibrary(PodioRefreshWorker.libraryFile(app))
        val alarm = library.state.value.alarm?.takeIf { it.on } ?: return
        runCatching { library.update { saved -> PodioHistory.played(saved.copy(alarm = saved.alarm?.let(PodioAlarms::fired)), alarm.station) } }
        sync(app)
        PodioRemotePlay.play(app, alarm.station.item().song(), gentle = alarm.gentle)
    }

    companion object {
        private const val ACTION_RING = "com.cosmotune.podio.ALARM_RING"
        private const val REQUEST = 4_701
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** Sets the system alarm for the radio alarm's next time, or clears it when the alarm is off. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val ring = PendingIntent.getBroadcast(app, REQUEST, Intent(app, PodioAlarmReceiver::class.java).setAction(ACTION_RING),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val at = PodioAlarms.next(runCatching { PodioLibrary(PodioRefreshWorker.libraryFile(app)).state.value.alarm }.getOrNull(), System.currentTimeMillis())
            if (at == null) { alarms.cancel(ring); return }
            try {
                if (exactAllowed(app)) {
                    // Shown by the system as the next alarm, and tapping it there opens the app.
                    val show = PendingIntent.getActivity(app, REQUEST, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ring)
                } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
            }
        }

        /** Whether the phone lets the app wake it on the minute. */
        fun exactAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

        /** The system page where exact alarms are allowed for the app. */
        fun allowIntent(context: Context): Intent? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
            else Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    }
}
