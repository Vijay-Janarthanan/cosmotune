package com.cosmotune.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    const val BEST_PART = "Best part button"
    const val BEST_PART_LINE = "A button on the player that skips to the part of the song people replay most, when YouTube knows it."
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
