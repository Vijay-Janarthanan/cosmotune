package com.cosmotune.music.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.cosmotune.music.data.TrackLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Runs the timed [SleepTimer] out: notices the deadline and pauses everything
 * the process plays.
 *
 * Process-wide rather than inside [PlaybackService], because not everything
 * that can be playing at the deadline is that service's: Video and Watch play
 * on Video's own service, and Music's may have stopped by then.
 *
 * Two ways to notice the deadline, because neither is enough alone:
 *
 *  - **A wait in short steps** ([SleepTimer.awaitDeadline]). A coroutine delay
 *    runs on uptime, which stops whenever the CPU sleeps, and with the screen
 *    off the CPU sleeps between audio buffer refills while the music carries
 *    on. One delay of the whole remainder is what kept a 30 minute timer
 *    playing for two hours. Steps read against elapsedRealtime, which counts
 *    through sleep, are late by one step of uptime at most.
 *  - **An alarm** at the deadline that wakes the phone, for the case where
 *    nothing else would (Doze, a long sleep with nothing to wake it).
 *
 * [checkDue] is the third, called from things that run while the phone is
 * awake anyway (the service's progress sampler and its player events).
 */
object SleepTimerWatcher {

    private const val ACTION_DUE = "com.cosmotune.music.action.SLEEP_TIMER_DUE"
    private const val ALARM_REQUEST = 0x51EE9

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pausers = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var app: Context? = null

    /** Starts watching; idempotent. Main thread. */
    fun start(context: Context) {
        if (app != null) return
        val application = context.applicationContext
        app = application
        ContextCompat.registerReceiver(
            application,
            object : BroadcastReceiver() {
                // On the main thread, while the alarm's own wake lock is held,
                // so the pause happens before the phone can sleep again.
                override fun onReceive(context: Context, intent: Intent) = checkDue()
            },
            IntentFilter(ACTION_DUE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            // `collectLatest` is what makes re-setting the timer work: the
            // pending wait is cancelled and restarted on the new deadline
            // instead of both firing.
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) {
                    cancelAlarm()
                    return@collectLatest
                }
                scheduleAlarm(deadline)
                SleepTimer.awaitDeadline(deadline)
                ranOut()
            }
        }
    }

    /** [pause] is called when the timer runs out, until [removePauser]. */
    fun addPauser(pause: () -> Unit) {
        pausers += pause
    }

    fun removePauser(pause: () -> Unit) {
        pausers -= pause
    }

    /** Pauses everything now if a timed timer is past its deadline. Main thread. */
    fun checkDue() {
        if (SleepTimer.isDue()) ranOut()
    }

    private fun ranOut() {
        TrackLog.i("SLEEP", "sleep timer ran out; pausing")
        SleepTimer.cancel()
        cancelAlarm()
        pausers.forEach { pause ->
            runCatching(pause).onFailure { TrackLog.w("SLEEP", "a pause failed: ${it.message}") }
        }
        // Every other mode's player (Video's service player, which Watch also plays on) adds its own pause to the hook.
        com.cosmotune.ui.sleep.SleepPausers.pauseAll().forEach { TrackLog.w("SLEEP", "a mode's pause failed: $it") }
        // A DLNA renderer playing the song ("Play on a TV or speaker").
        runCatching { com.cosmotune.music.data.cast.dlna.DlnaCast.pauseRenderer() }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST,
        Intent(ACTION_DUE).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Wakes the phone at [deadline] (elapsedRealtime). Exact where the app may
     * set exact alarms; otherwise the inexact while-idle alarm, which needs no
     * permission and still wakes the phone near the deadline, the stepped wait
     * and [checkDue] covering the difference.
     */
    private fun scheduleAlarm(deadline: Long) {
        val context = app ?: return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val type = AlarmManager.ELAPSED_REALTIME_WAKEUP
        val intent = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(type, deadline, intent)
            } else {
                alarms.setAndAllowWhileIdle(type, deadline, intent)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(type, deadline, intent)
        }
    }

    private fun cancelAlarm() {
        val context = app ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }
}

/** [SleepTimer] as the shared screens reach it (the player's pill and menu). */
object AndroidSleepTimerControl : com.cosmotune.ui.sleep.SleepTimerControl {
    override val minutes: kotlinx.coroutines.flow.StateFlow<Int?> get() = SleepTimer.minutes
    override val afterTrack: kotlinx.coroutines.flow.StateFlow<Boolean> get() = SleepTimer.afterTrack
    override val presets: List<Int> get() = SleepTimer.PRESETS
    override fun remainingMs(): Long? = SleepTimer.remainingMs()
    override fun start(minutes: Int) = SleepTimer.start(minutes)
    override fun startAfterTrack() = SleepTimer.startAfterTrack()
    override fun extend() = SleepTimer.extend()
    override fun cancel() = SleepTimer.cancel()
}
