package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.settings.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.settings.CosmoSettingsChip
import com.cosmotune.ui.settings.CosmoSliderRow
import com.cosmotune.ui.sheets.CosmoAlertDialog
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerOptions(songId: String, bluetoothSupported: Boolean, onDismiss: () -> Unit, settings: ExtrasSettings = Extras.settings) {
    val overrides by settings.songPlayback.collectAsState()
    val tuning=overrides[songId]
    val inline by settings.inlineLyrics.collectAsState()
    val alignment by settings.lyricAlignment.collectAsState()
    val romanisedOnly by settings.romanisedOnly.collectAsState()
    val reconnect by settings.bluetoothResume.collectAsState()
    val mute by settings.pauseOnMute.collectAsState()
    val current=tuning ?: SongPlaybackTuning()
    CosmoAlertDialog(onDismissRequest=onDismiss,title="Player options",confirmLabel="Done",onConfirm=onDismiss,content={
        if(SongPlaybackMemory.isMusic(songId)) {
            PlayerOptionSwitch("For this song","Remember a speed, key and sound effect for this recording. Other songs follow the defaults.",tuning!=null,
                { settings.setSongPlayback(songId,if(it) current else null) })
            if(tuning!=null) {
                CosmoSliderRow(icon=CosmoIcons.Play,title="Song speed",subtitle="Changes this recording only.",
                    value="%.2f×".format(java.util.Locale.ROOT,current.speed),sliderValue=current.speed,
                    onSliderValue={ settings.setSongPlayback(songId,current.copy(speed=(it*20).roundToInt()/20f)) },valueRange=0.25f..3f,steps=54)
                CosmoSliderRow(icon=CosmoIcons.Equaliser,title="Song pitch",subtitle="Semitones, independent of speed.",
                    value=current.pitchSemitones.toString(),sliderValue=current.pitchSemitones.toFloat(),
                    onSliderValue={ settings.setSongPlayback(songId,current.copy(pitchSemitones=it.roundToInt())) },valueRange=-12f..12f,steps=23)
                // The song's own sound effect: "As in Settings" follows Settings > Sound > Sound effects.
                CosmoSettingsRow(icon=CosmoIcons.Waveform,title="Song sound effect",subtitle="Plays this recording with its own effect, or with none.")
                FlowRow(Modifier.fillMaxWidth().padding(horizontal=12.dp).padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    CosmoSettingsChip("As in Settings",current.effect==null,{ settings.setSongPlayback(songId,current.copy(effect=null)) })
                    com.cosmotune.audio.SoundEffect.entries.forEach { option ->
                        CosmoSettingsChip(option.label,current.effect==option.name,{ settings.setSongPlayback(songId,current.copy(effect=option.name)) })
                    }
                }
            }
        }
        val fresh by settings.freshShuffle.collectAsState()
        PlayerOptionSwitch("Fresh shuffle","Shuffle still plays everything, but the songs you heard lately tend to come later.",fresh,settings::setFreshShuffle)
        PlayerOptionSwitch("Player lyrics","Show timed lyrics beside the artwork when there is room. The Lyrics panel remains available.",inline,settings::setInlineLyrics)
        PlayerOptionSwitch("Romanised lyrics only","With Romanise on, show each line in Latin letters alone instead of under the original script.",romanisedOnly,settings::setRomanisedOnly)
        CosmoSettingsRow(icon=CosmoIcons.Lyrics,title="Orbit Read alignment",subtitle="Auto follows each line's writing direction. Duet voices keep their own lanes.")
        FlowRow(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            LyricTextAlignment.entries.forEach { option -> CosmoSettingsChip(if(option==LyricTextAlignment.AUTO) "Auto" else "Center",option==alignment,{settings.setLyricAlignment(option)}) }
        }
        if(bluetoothSupported) PlayerOptionSwitch("Bluetooth resume","Resume the same paused song after headphones disconnected. A manual pause cancels the pending resume.",reconnect,settings::setBluetoothResume)
        PlayerOptionSwitch("Pause on mute",if(bluetoothSupported) "Pause music when media volume reaches zero. Turn it back up and the song plays on." else "Pause music when Cosmotune volume reaches zero. Turn it back up and the song plays on.",mute,settings::setPauseOnMute)
    })
}

@Composable
private fun PlayerOptionSwitch(title: String, line: String, checked: Boolean, change: (Boolean)->Unit) {
    CosmoSettingsRow(icon=CosmoIcons.Equaliser,title=title,subtitle=line,onClick={change(!checked)},
        trailing={ CosmoSwitch(checked=checked,onCheckedChange=change) })
}
