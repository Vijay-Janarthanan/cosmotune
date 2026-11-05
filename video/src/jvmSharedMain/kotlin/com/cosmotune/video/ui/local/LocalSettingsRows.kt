package com.cosmotune.video.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.local.VideoAspect
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_aspect
import com.cosmotune.video.resources.video_aspect_16_9
import com.cosmotune.video.resources.video_aspect_21_9
import com.cosmotune.video.resources.video_aspect_4_3
import com.cosmotune.video.resources.video_aspect_fill
import com.cosmotune.video.resources.video_aspect_fit
import com.cosmotune.video.resources.video_aspect_original
import com.cosmotune.video.resources.video_aspect_stretch
import com.cosmotune.video.resources.video_local_remember
import com.cosmotune.video.resources.video_local_subtitle_file
import com.cosmotune.video.resources.video_off
import com.cosmotune.video.resources.video_on
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The extra page of the player's settings for a local file (the rest are the watch page's). */
internal enum class LocalSettingsPage { NONE, ASPECT }

/** The name of each aspect choice. */
internal fun aspectLabel(aspect: VideoAspect): StringResource = when (aspect) {
    VideoAspect.FIT -> Res.string.video_aspect_fit
    VideoAspect.FILL -> Res.string.video_aspect_fill
    VideoAspect.STRETCH -> Res.string.video_aspect_stretch
    VideoAspect.R16_9 -> Res.string.video_aspect_16_9
    VideoAspect.R4_3 -> Res.string.video_aspect_4_3
    VideoAspect.R21_9 -> Res.string.video_aspect_21_9
    VideoAspect.ORIGINAL -> Res.string.video_aspect_original
}

/**
 * The rows a local file adds to the player's settings, after the common ones: Aspect ratio (opens its list, [onAspect]), Subtitle file
 * (opens the picker, then [onDone] closes the settings) and Remember position (a switch kept with the recent list).
 */
@Composable
internal fun LocalSettingsRows(menu: Boolean, onAspect: () -> Unit, onDone: () -> Unit) {
    val store = rememberLocalRecentStore()
    val remember by store.rememberPositions.collectAsState()
    val picker = LocalPlayerSession.subtitlePicker
    val on = stringResource(Res.string.video_on)
    val off = stringResource(Res.string.video_off)
    CosmoActionRow(CosmoIcons.Expand, stringResource(Res.string.video_aspect), value = stringResource(aspectLabel(LocalPlayerSession.aspect)), menu = menu) { onAspect() }
    CosmoActionRow(VideoIcons.Captions, stringResource(Res.string.video_local_subtitle_file), enabled = picker != null, menu = menu) {
        onDone()
        picker?.invoke()
    }
    CosmoActionRow(CosmoIcons.Clock, stringResource(Res.string.video_local_remember), value = if (remember) on else off, menu = menu) {
        store.setRememberPositions(!remember)
    }
}

/** The list of aspect choices, with a back row; picking one applies it to the picture at once and closes the settings ([done]). */
@Composable
internal fun LocalAspectPage(menu: Boolean, back: () -> Unit, done: () -> Unit) {
    val accent = Color(LocalCosmoColors.current.palette.accent)
    CosmoActionRow(icon = CosmoIcons.ChevronLeft, label = stringResource(Res.string.video_aspect), menu = menu, onClick = back)
    for (aspect in VideoAspect.entries) {
        val selected = aspect == LocalPlayerSession.aspect
        CosmoActionRow(
            icon = if (selected) CosmoIcons.Check else BlankAspectIcon,
            label = stringResource(aspectLabel(aspect)),
            tint = if (selected) accent else null,
            menu = menu,
        ) {
            LocalPlayerSession.aspect = aspect
            done()
        }
    }
}

/** The empty icon of a list row that is not the picked one (so the labels line up). */
private val BlankAspectIcon: ImageVector = ImageVector.Builder(
    name = "blank-aspect", defaultWidth = androidx.compose.ui.unit.Dp(24f), defaultHeight = androidx.compose.ui.unit.Dp(24f), viewportWidth = 24f, viewportHeight = 24f,
).build()
