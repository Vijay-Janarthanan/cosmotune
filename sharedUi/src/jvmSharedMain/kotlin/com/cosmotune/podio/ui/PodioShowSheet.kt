@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.cosmotune.podio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodcastShow
import com.cosmotune.podio.PodioSaved
import com.cosmotune.podio.PodioShowRules
import com.cosmotune.podio.PodioShowSettings
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.*

/** "Off", "30 s", "2 min". */
internal fun skipLength(seconds: Int): String = when {
    seconds <= 0 -> "Off"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "$seconds s"
}

/** "All", "Newest 3". */
internal fun keepLength(count: Int): String = if (count <= 0) "All" else "Newest $count"

/** The speeds a show can keep as its own. */
private val SHOW_SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * A followed show's own settings as a sheet (Pocket Casts' podcast settings): what happens with its new episodes (say so, download,
 * add to Up next), how it plays (skip the intro and the outro, skip silence, its speed) and its downloads (remove once played, keep
 * the newest few). A choice left alone follows Settings › Podio, and the line under it says what that is. [save] changes the library.
 */
@Composable internal fun ShowSettingsSheet(show: PodcastShow, saved: PodioSaved, save: ((PodioSaved) -> PodioSaved) -> Unit, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val own = PodioShowRules.settings(saved, show.id)
    fun change(edit: (PodioShowSettings) -> PodioShowSettings) = save { PodioShowRules.change(it, show.id, edit) }
    fun app(on: Boolean) = if (on) "Like the app: on" else "Like the app: off"
    KitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
            .testTag("podio:show-settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitLabel("Show settings", fonts, look.dim)
            Text(show.title.ifBlank { "Podcast" }, style = fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 26.sp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)

            OrbitLabel("New episodes", fonts, look.dim, Modifier.padding(top = 16.dp))
            OrbitSwitchRow("Tell me about new episodes", own.notify, { on -> change { it.copy(notify = on) } }, plain = true,
                switchModifier = Modifier.testTag("podio:show-settings:notify"))
            OrbitSwitchRow("Download new episodes", PodioShowRules.autoDownload(saved, show.id), { on -> change { it.copy(autoDownload = on) } },
                detail = if (own.autoDownload == null) app(saved.autoDownloadNew) else "For this show", plain = true,
                switchModifier = Modifier.testTag("podio:show-settings:download"))
            OrbitSwitchRow("Add new episodes to Up next", own.autoQueue, { on -> change { it.copy(autoQueue = on) } }, detail = "At the end of your queue", plain = true,
                switchModifier = Modifier.testTag("podio:show-settings:queue"))

            OrbitLabel("Playback", fonts, look.dim, Modifier.padding(top = 16.dp))
            Text("Skip the intro", style = fonts.body, color = look.ink)
            OrbitChipRow(0.dp) {
                PodioShowRules.SKIP_CHOICES.forEach { seconds ->
                    OrbitSelectChip(skipLength(seconds), own.skipIntroSeconds == seconds, fonts, look, { change { it.copy(skipIntroSeconds = seconds) } }, haptic = false,
                        modifier = Modifier.testTag("podio:show-settings:intro:$seconds"))
                }
            }
            Text("Skip the outro", style = fonts.body, color = look.ink, modifier = Modifier.padding(top = 8.dp))
            OrbitChipRow(0.dp) {
                PodioShowRules.SKIP_CHOICES.forEach { seconds ->
                    OrbitSelectChip(skipLength(seconds), own.skipOutroSeconds == seconds, fonts, look, { change { it.copy(skipOutroSeconds = seconds) } }, haptic = false,
                        modifier = Modifier.testTag("podio:show-settings:outro:$seconds"))
                }
            }
            Text("Speed", style = fonts.body, color = look.ink, modifier = Modifier.padding(top = 8.dp))
            val speed = saved.speedByShow[show.id] ?: 1f
            OrbitChipRow(0.dp) {
                SHOW_SPEEDS.forEach { choice ->
                    OrbitSelectChip(PodioFormat.speed(choice), kotlin.math.abs(speed - choice) < 0.01f, fonts, look, {
                        save { old -> old.copy(speedByShow = if (choice == 1f) old.speedByShow - show.id else old.speedByShow + (show.id to choice)) }
                    }, haptic = false)
                }
            }
            val skip = saved.skipSilenceByShow[show.id]
            OrbitSwitchRow("Skip silence", skip == true, { on -> save { old -> old.copy(skipSilenceByShow = old.skipSilenceByShow + (show.id to on)) } },
                detail = if (skip == null) "Like the app" else "For this show", plain = true, modifier = Modifier.padding(top = 4.dp))
            OrbitSwitchRow("Voice boost", PodioShowRules.voiceBoost(saved, show.id), { on -> change { it.copy(voiceBoost = on) } },
                detail = if (own.voiceBoost == null) app(saved.voiceBoost) else "Louder, clearer speech for this show", plain = true,
                switchModifier = Modifier.testTag("podio:show-settings:voice-boost"))

            OrbitLabel("Tags", fonts, look.dim, Modifier.padding(top = 16.dp))
            val mine = saved.showTags[show.id].orEmpty()
            val known = (com.cosmotune.podio.PodioTags.all(saved) + mine).distinctBy { it.lowercase() }
            var typed by remember(show.id) { mutableStateOf("") }
            if (known.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                known.forEach { each ->
                    val on = mine.any { it.equals(each, ignoreCase = true) }
                    OrbitSelectChip(each, on, fonts, look, {
                        save { com.cosmotune.podio.PodioTags.set(it, show.id, if (on) mine.filterNot { t -> t.equals(each, ignoreCase = true) } else mine + each) }
                    }, haptic = false)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitInputField(typed, { typed = it.take(30) }, "New tag, like News", modifier = Modifier.weight(1f).testTag("podio:show-settings:tag"))
                OrbitSelectChip("Add", false, fonts, look, {
                    if (typed.isNotBlank()) { save { com.cosmotune.podio.PodioTags.set(it, show.id, mine + typed) }; typed = "" }
                }, icon = com.cosmotune.ui.components.CosmoIcons.Plus, haptic = false)
            }

            OrbitLabel("Downloads", fonts, look.dim, Modifier.padding(top = 16.dp))
            OrbitSwitchRow("Remove once played", PodioShowRules.deletePlayed(saved, show.id), { on -> change { it.copy(deletePlayed = on) } },
                detail = if (own.deletePlayed == null) app(saved.deletePlayedDownloads) else "For this show", plain = true,
                switchModifier = Modifier.testTag("podio:show-settings:delete-played"))
            Text("Downloads to keep", style = fonts.body, color = look.ink)
            OrbitChipRow(0.dp) {
                PodioShowRules.KEEP_CHOICES.forEach { count ->
                    OrbitSelectChip(keepLength(count), PodioShowRules.keepDownloads(saved, show.id) == count, fonts, look, { change { it.copy(keepDownloads = count) } }, haptic = false)
                }
            }
            Text("Episodes you have started always stay.", style = fonts.body.copy(fontSize = 13.sp), color = look.dim)

            val custom = !own.isDefault || show.id in saved.speedByShow || show.id in saved.skipSilenceByShow
            if (custom) PodioTextAction("Use the app's settings", look, fonts, {
                save { old -> PodioShowRules.change(old, show.id) { PodioShowSettings() }.let { it.copy(speedByShow = it.speedByShow - show.id, skipSilenceByShow = it.skipSilenceByShow - show.id) } }
            }, Modifier.padding(top = 12.dp).testTag("podio:show-settings:reset"))
        }
    }
}
