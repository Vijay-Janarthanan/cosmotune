package com.cosmotune.podio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioAlarm
import com.cosmotune.podio.PodioAlarms
import com.cosmotune.podio.PodioSaved
import com.cosmotune.podio.RadioStation
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitMenuPill
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.delay

/**
 * Settings › Podio › Radio alarm: a saved (or lately played) station that starts at a time on chosen days. [exactAlarms] is false on a
 * phone that has not let the app wake it on the minute; [onAllowExactAlarms] then opens the system's page for it.
 */
@Composable
internal fun ColumnScope.PodioAlarmSettings(saved: PodioSaved, save: ((PodioSaved) -> PodioSaved) -> Unit,
    exactAlarms: Boolean = true, onAllowExactAlarms: (() -> Unit)? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val choices = (saved.stations + saved.recentStations).distinctBy { it.id }
    val alarm = saved.alarm
    fun change(next: PodioAlarm?) = save { it.copy(alarm = next) }
    fun edit(how: (PodioAlarm) -> PodioAlarm) { alarm?.let { change(how(it)) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(alarm) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val next = PodioAlarms.next(alarm, now)

    OrbitLabel("Radio alarm", fonts, look.dim, Modifier.padding(top = 8.dp))
    OrbitSwitchRow("Wake to the radio", alarm?.on == true, { on ->
        if (alarm != null) edit { it.copy(on = on) } else choices.firstOrNull()?.let { change(PodioAlarm(it)) }
    }, detail = when {
        alarm == null && choices.isEmpty() -> "Save a station first, then choose it here"
        alarm == null -> "Starts a station you choose, at the time you choose"
        next != null -> PodioAlarms.summary(alarm) + " · " + PodioAlarms.until(next, now)
        else -> PodioAlarms.summary(alarm) + " · off"
    }, enabled = alarm != null || choices.isNotEmpty(), plain = true, switchModifier = Modifier.testTag("settings:podio:alarm"))
    if (alarm == null) return
    var stationMenu by remember { mutableStateOf(false) }
    var hourMenu by remember { mutableStateOf(false) }
    var minuteMenu by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbitMenuPill(alarm.station.name, stationMenu, { stationMenu = true }, { stationMenu = false },
            (listOf(alarm.station) + choices).distinctBy(RadioStation::id).take(40).map { station ->
                OrbitMenuItem(station.name, detail = station.country.takeIf(String::isNotBlank), selected = station.id == alarm.station.id) {
                    stationMenu = false; edit { it.copy(station = station) }
                }
            }, Modifier.testTag("settings:podio:alarm-station"), header = "Station")
        OrbitMenuPill("%02d".format(alarm.hour), hourMenu, { hourMenu = true }, { hourMenu = false },
            (0..23).map { hour -> OrbitMenuItem("%02d".format(hour), selected = hour == alarm.hour) { hourMenu = false; edit { it.copy(hour = hour) } } },
            Modifier.testTag("settings:podio:alarm-hour"), header = "Hour")
        OrbitMenuPill("%02d".format(alarm.minute), minuteMenu, { minuteMenu = true }, { minuteMenu = false },
            (0..55 step 5).map { minute -> OrbitMenuItem("%02d".format(minute), selected = minute == alarm.minute) { minuteMenu = false; edit { it.copy(minute = minute) } } },
            Modifier.testTag("settings:podio:alarm-minute"), header = "Minute")
    }
    Text("Days", style = fonts.body, color = look.ink)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PodioAlarms.DAY_NAMES.forEachIndexed { index, name ->
            val day = index + 1
            OrbitSelectChip(name, day in alarm.days, fonts, look, { edit { it.copy(days = if (day in it.days) it.days - day else it.days + day) } }, haptic = false)
        }
    }
    Text(if (alarm.days.isEmpty()) "No day chosen: it goes off once, then turns itself off." else "Goes off ${PodioAlarms.days(alarm.days)}.",
        style = fonts.body.copy(fontSize = 13.sp), color = look.dim)
    OrbitSwitchRow("Start softly", alarm.gentle, { on -> edit { it.copy(gentle = on) } },
        detail = "From quiet to full volume over half a minute", plain = true, switchModifier = Modifier.testTag("settings:podio:alarm-gentle"))
    if (!exactAlarms) {
        Text("The phone may start it a few minutes late until Cosmotune is allowed to set exact alarms.",
            style = fonts.body.copy(fontSize = 13.sp), color = look.dim)
        onAllowExactAlarms?.let { allow ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitSelectChip("Allow exact alarms", false, fonts, look, allow, haptic = false, icon = CosmoIcons.Settings)
            }
        }
    }
}
