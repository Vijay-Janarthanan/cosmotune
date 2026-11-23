package com.cosmotune.video.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_screenshot
import com.cosmotune.video.resources.video_screenshot_failed
import com.cosmotune.video.resources.video_screenshot_saved
import com.cosmotune.video.ui.VideoIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Saves the picture showing now: to Pictures/Cosmotune on the phone, to Pictures/Cosmotune and the clipboard on the desktop. */
interface VideoScreenshots {
    /** Saves the picture as a PNG named after [title] and the moment; false when there is no picture or it could not be saved. */
    suspend fun take(title: String, positionMs: Long): Boolean
}

/** The screenshots of [playback]'s picture here, or null where they cannot be taken. */
@Composable
internal expect fun rememberVideoScreenshots(playback: VideoPlayback): VideoScreenshots?

/** "Title 12-04.png": the title without the characters file systems refuse, and the moment. */
internal fun screenshotName(title: String, positionMs: Long): String {
    val clean = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("""\s+"""), " ").trim().take(80).ifEmpty { "Cosmotune" }
    val total = positionMs.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val at = if (h > 0) "%d-%02d-%02d".format(h, m, s) else "%02d-%02d".format(m, s)
    return "$clean $at.png"
}

/** "Save a screenshot" in the player's settings: the picture at the moment of the tap; the row then says whether it was saved. */
@Composable
internal fun ScreenshotRow(playback: VideoPlayback, state: PlaybackUiState, menu: Boolean) {
    val shots = rememberVideoScreenshots(playback) ?: return
    if (state.item == null || state.audioOnly) return
    val scope = rememberCoroutineScope()
    var shot by rememberSaveable { mutableStateOf<Boolean?>(null) }
    CosmoActionRow(
        VideoIcons.Camera, stringResource(Res.string.video_screenshot),
        value = shot?.let { stringResource(if (it) Res.string.video_screenshot_saved else Res.string.video_screenshot_failed) },
        menu = menu,
    ) {
        val title = state.item?.title.orEmpty()
        val at = playback.positionMs()
        scope.launch { shot = shots.take(title, at) }
    }
}
