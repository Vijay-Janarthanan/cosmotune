package com.cosmotune.ui.sleep

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * The host's sleep timer as the shared screens see it: the phone's and the TV's
 * `SleepTimer`, the desktop's `DesktopSleepTimer`. The deadline itself stays
 * the host's; this only reads it and asks for changes.
 */
interface SleepTimerControl {
    /** How long the timer was set for, extensions included; changes with every start, extension and cancel. */
    val minutes: StateFlow<Int?>
    val afterTrack: StateFlow<Boolean>
    /** How long is left of a timed timer, or null when none is counting. */
    fun remainingMs(): Long?
    val presets: List<Int> get() = listOf(15, 30, 45, 60)
    fun start(minutes: Int)
    fun startAfterTrack()
    /** Adds [EXTEND_MINUTES] to what is left (or starts that long a timer). */
    fun extend()
    fun cancel()

    companion object {
        const val EXTEND_MINUTES = 15
    }
}

/** Where the shared screens find the host's timer; null where there is none (a test, a host without one). */
object SleepTimerUi {
    @Volatile
    var control: SleepTimerControl? = null
}

/** A running timer as the screens draw it: [remainingMs] for a timed one, or [afterTrack]. */
@Immutable
data class SleepTimerReading(val remainingMs: Long?, val afterTrack: Boolean) {
    /** "23:41", or [afterLabel]: what the pill says. */
    fun short(afterLabel: String = AFTER_THIS_SONG): String = remainingMs?.let(::sleepCountdown) ?: afterLabel

    /** "23:41 left", or [afterLabel]: what a menu row says after "Sleep timer". */
    fun line(afterLabel: String = AFTER_THIS_SONG): String = remainingMs?.let { "${sleepCountdown(it)} left" } ?: afterLabel

    companion object {
        const val AFTER_THIS_SONG = "After this song"
    }
}

/**
 * "m:ss" under an hour, "h:mm:ss" from there, rounded up to the second so the
 * pill never says 0:00 while the music still plays.
 */
fun sleepCountdown(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The running timer, re-read every second while it counts; null when it is off or there is no host timer. */
@Composable
fun rememberSleepTimer(control: SleepTimerControl? = SleepTimerUi.control): SleepTimerReading? {
    if (control == null) return null
    val minutes by control.minutes.collectAsState()
    val afterTrack by control.afterTrack.collectAsState()
    val remaining by produceState(control.remainingMs(), control, minutes) {
        value = control.remainingMs()
        while (value != null) {
            // To the next whole second of the countdown, so the pill turns over
            // with the seconds rather than up to a second behind them.
            delay(((value ?: 0L) % 1000L).takeIf { it > 0L } ?: 1000L)
            value = control.remainingMs()
        }
    }
    return when {
        remaining != null -> SleepTimerReading(remaining, false)
        afterTrack -> SleepTimerReading(null, true)
        else -> null
    }
}

/**
 * The sleep timer's menu, the kit's [OrbitMenu]: the time left in its header,
 * Extend and Turn off while one runs, then the choices (the end of the song,
 * the presets).
 */
@Composable
fun SleepTimerMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    control: SleepTimerControl? = SleepTimerUi.control,
    afterLabel: String = SleepTimerReading.AFTER_THIS_SONG,
    width: Dp = 240.dp,
) {
    if (control == null) return
    val reading = rememberSleepTimer(control)
    val minutes by control.minutes.collectAsState()
    val items = sleepTimerMenuItems(control, reading, minutes, afterLabel)
    val header = reading?.let { "Sleep timer · ${it.line(afterLabel)}" } ?: "Sleep timer"
    OrbitMenu(open, onDismiss, items, fonts, look, header = header, width = width)
}

/** The rows of [SleepTimerMenu], apart so a test can read them. */
fun sleepTimerMenuItems(
    control: SleepTimerControl,
    reading: SleepTimerReading?,
    minutes: Int?,
    afterLabel: String = SleepTimerReading.AFTER_THIS_SONG,
): List<OrbitMenuItem> = buildList {
    if (reading != null) {
        add(OrbitMenuItem("Extend", "Add ${SleepTimerControl.EXTEND_MINUTES} minutes", CosmoIcons.Plus, keepOpen = true) { control.extend() })
        add(OrbitMenuItem("Turn off timer", null, CosmoIcons.Close) { control.cancel() })
    }
    add(OrbitMenuItem(afterLabel, null, selected = reading?.afterTrack == true) { control.startAfterTrack() })
    control.presets.forEach { preset ->
        add(OrbitMenuItem(if (preset == 60) "1 hour" else "$preset minutes", null, selected = reading?.remainingMs != null && minutes == preset) { control.start(preset) })
    }
}

/**
 * The pill a running timer shows in a chip row: solid, the moon and "23:41"
 * (or "After this song"). Tapping it opens [SleepTimerMenu]. Draws nothing
 * while no timer runs.
 */
@Composable
fun SleepTimerChip(
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    control: SleepTimerControl? = SleepTimerUi.control,
    afterLabel: String = SleepTimerReading.AFTER_THIS_SONG,
) {
    val reading = rememberSleepTimer(control) ?: return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OrbitChip(
            reading.short(afterLabel), fonts, look, onClick = { open = true }, selected = true,
            leading = { Icon(CosmoIcons.Timer, contentDescription = "Sleep timer", modifier = Modifier.size(18.dp)) },
        )
        SleepTimerMenu(open, { open = false }, fonts, look, control, afterLabel)
    }
}
