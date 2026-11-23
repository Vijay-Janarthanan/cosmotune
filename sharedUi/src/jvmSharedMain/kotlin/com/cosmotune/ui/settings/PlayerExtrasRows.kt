package com.cosmotune.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.settings.ScreenOnPolicy
import androidx.compose.ui.graphics.vector.ImageVector
import com.cosmotune.settings.Extras
import com.cosmotune.settings.ExtrasSettings
import com.cosmotune.ui.components.CosmoSwitch

/** The words of the player options that came from upstream 1.10, shared by the phone's and the desktop's settings and their search. */
object PlayerExtrasText {
    const val GROUP = "Player"
    const val PREMIUM = "YouTube Premium audio"
    const val PREMIUM_LINE = "I have Premium: stream the clearer ~256 kbps Opus instead of the usual 160 kbps. Needs you signed in, " +
        "and applies from the next song."
    const val SLEEP_FADE = "Fade out before the sleep timer stops"
    const val SLEEP_FADE_LINE = "The volume goes down over the last 30 seconds."
    const val SCREEN_ON = "Keep the screen on"
    const val SCREEN_ON_LINE = "The lyrics and videos always keep it on. Choose to keep it on while the player is open, or whenever music plays."
    const val BEST_PART = "Best part button"
    const val BEST_PART_LINE = "A button on the player that skips to the part of the song people replay most, or else to the chorus in its lyrics."
    const val NOTIFICATION_SLEEP = "Sleep timer on the notification"
    const val NOTIFICATION_SLEEP_LINE = "The notification's second button sets the sleep timer instead of Repeat: each tap goes 15, 30, 60 minutes, then off."
    const val WAVEFORM = "Waveform seek line"
    const val WAVEFORM_LINE = "The seek line shows how loud the song gets once you have heard it, with dots where listeners left a comment about a moment."
}

/** YouTube Premium's higher Opus rung for a signed-in account ([ExtrasSettings.premiumAudio]); off until turned on. */
@Composable
fun PremiumAudioRow(settings: ExtrasSettings = Extras.settings) {
    val on by settings.premiumAudio.collectAsState()
    ExtrasSwitchRow(Icons.Rounded.GraphicEq, PlayerExtrasText.PREMIUM, PlayerExtrasText.PREMIUM_LINE, on, settings::setPremiumAudio)
}

/** The sleep timer's 30-second fade ([ExtrasSettings.sleepFadeOut]); on unless turned off. */
@Composable
fun SleepFadeOutRow(settings: ExtrasSettings = Extras.settings) {
    val on by settings.sleepFadeOut.collectAsState()
    ExtrasSwitchRow(Icons.Rounded.Bedtime, PlayerExtrasText.SLEEP_FADE, PlayerExtrasText.SLEEP_FADE_LINE, on, settings::setSleepFadeOut)
}

/** The player's Best part button ([ExtrasSettings.bestPart]); on unless turned off. */
@Composable
fun BestPartRow(settings: ExtrasSettings = Extras.settings) {
    val on by settings.bestPart.collectAsState()
    ExtrasSwitchRow(Icons.Rounded.Whatshot, PlayerExtrasText.BEST_PART, PlayerExtrasText.BEST_PART_LINE, on, settings::setBestPart)
}

/** The sleep timer in place of Repeat on the phone notification ([ExtrasSettings.notificationSleep]); off until turned on. */
@Composable
fun NotificationSleepRow(settings: ExtrasSettings = Extras.settings) {
    val on by settings.notificationSleep.collectAsState()
    ExtrasSwitchRow(Icons.Rounded.Bedtime, PlayerExtrasText.NOTIFICATION_SLEEP, PlayerExtrasText.NOTIFICATION_SLEEP_LINE, on, settings::setNotificationSleep)
}

/** The waveform seek line with timed comments ([ExtrasSettings.waveformSeek]); on until turned off. */
@Composable
fun WaveformSeekRow(settings: ExtrasSettings = Extras.settings) {
    val on by settings.waveformSeek.collectAsState()
    ExtrasSwitchRow(com.cosmotune.ui.components.CosmoIcons.Waveform, PlayerExtrasText.WAVEFORM, PlayerExtrasText.WAVEFORM_LINE, on, settings::setWaveformSeek)
}

/** When the screen stays on ([ExtrasSettings.screenOn]): a short pill in the row's own column, the full choices in its menu. */
@Composable
fun ScreenOnRow(settings: ExtrasSettings = Extras.settings) {
    val policy by settings.screenOn.collectAsState()
    var open by remember { mutableStateOf(false) }
    CosmoSettingsRow(
        icon = Icons.Rounded.LightMode,
        title = PlayerExtrasText.SCREEN_ON,
        subtitle = PlayerExtrasText.SCREEN_ON_LINE,
        trailing = {
            com.cosmotune.ui.orbit.OrbitMenuPill(
                text = policy.short,
                open = open,
                onOpen = { open = true },
                onDismiss = { open = false },
                items = ScreenOnPolicy.entries.map { choice ->
                    com.cosmotune.ui.orbit.OrbitMenuItem(choice.label, selected = choice == policy) { settings.setScreenOn(choice); open = false }
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        },
    )
}

@Composable
private fun ExtrasSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    CosmoSettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onChange(!checked) },
        trailing = { CosmoSwitch(checked = checked, onCheckedChange = onChange) },
    )
}
