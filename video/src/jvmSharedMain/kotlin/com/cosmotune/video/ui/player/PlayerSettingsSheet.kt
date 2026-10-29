package com.cosmotune.video.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.sheets.cosmoMenuSurface
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerKeyMap
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_audio_descriptive
import com.cosmotune.video.resources.video_audio_dubbed
import com.cosmotune.video.resources.video_audio_original
import com.cosmotune.video.resources.video_audio_track
import com.cosmotune.video.resources.video_captions
import com.cosmotune.video.resources.video_dl_auto_generated
import com.cosmotune.video.resources.video_loop
import com.cosmotune.video.resources.video_off
import com.cosmotune.video.resources.video_on
import com.cosmotune.video.resources.video_quality
import com.cosmotune.video.resources.video_quality_auto
import com.cosmotune.video.resources.video_quality_auto_value
import com.cosmotune.video.resources.video_speed
import com.cosmotune.video.resources.video_speed_value
import com.cosmotune.video.resources.video_smooth_title
import com.cosmotune.video.resources.video_stats
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.local.LocalAspectPage
import com.cosmotune.video.ui.local.LocalSettingsPage
import com.cosmotune.video.ui.local.LocalSettingsRows
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The desktop menu (spec 6.5: a C menu, radius 22, 44 dp items) is this wide and scrolls past this height. */
private const val MENU_WIDTH_DP = 280f
private const val MENU_MAX_HEIGHT_DP = 420f

/** The speed slider snaps to hundredths of 0.05 (the list has the quarters). */
private const val SLIDER_SNAP = 0.05f

/** The slider's side padding inside the sheet (the rows' 8 + 14). */
private const val SLIDER_PAD_DP = 22f

/** The speeds of the list (spec 6.5: 0.25 to 4 in steps of 0.25). */
internal val SPEEDS: List<Float> = (1..16).map { it * PlayerKeyMap.SPEED_STEP }

/** The pages of the settings: the rows, and the list each row opens in the same sheet. */
internal enum class SettingsPage { MAIN, QUALITY, SPEED, CAPTIONS, AUDIO, SMOOTHNESS }

/**
 * The phone's player settings (spec 6.5): a C sheet (`CosmoSheetSurface`, through the app's sheet host) with the rows Quality ("Auto ·
 * 1080p60"), Speed ("1x"), Captions, Audio track ("Original"), Loop and Stats; each of the first four opens its list in the same sheet
 * with a back row. Picking an entry closes the sheet; Loop and Stats switch where they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    statsShown: Boolean,
    onToggleStats: () -> Unit,
    onDismiss: () -> Unit,
) {
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PlayerSettingsPanel(playback, state, settings, statsShown, onToggleStats, menu = false, onDone = onDismiss)
    }
}

/**
 * The desktop's player settings (spec 6.5): a C menu (`cosmoMenuSurface`: radius 22, rows of 44 dp) under the settings button, with
 * the same rows and lists as the sheet. Call it inside the box that holds the button: it opens below the button, aligned to its end.
 */
@Composable
fun PlayerSettingsMenu(
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    statsShown: Boolean,
    onToggleStats: () -> Unit,
    onDismiss: () -> Unit,
) {
    val below = with(LocalDensity.current) { VideoMetrics.CHROME_ICON_DP.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, below),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(MENU_WIDTH_DP.dp)
                .heightIn(max = MENU_MAX_HEIGHT_DP.dp)
                .cosmoMenuSurface()
                .verticalScroll(rememberScrollState()),
        ) {
            PlayerSettingsPanel(playback, state, settings, statsShown, onToggleStats, menu = true, onDone = onDismiss)
        }
    }
}

/** The rows of the settings and the list each opens (spec 6.5), as sheet rows or, with [menu], as menu rows. */
@Composable
internal fun PlayerSettingsPanel(
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    statsShown: Boolean,
    onToggleStats: () -> Unit,
    menu: Boolean,
    onDone: () -> Unit,
    startPage: SettingsPage = SettingsPage.MAIN,
) {
    var page by rememberSaveable { mutableStateOf(startPage) }
    val accent = Color(LocalCosmoColors.current.palette.accent)
    val on = stringResource(Res.string.video_on)
    val off = stringResource(Res.string.video_off)
    val original = stringResource(Res.string.video_audio_original)
    val autoGenerated = stringResource(Res.string.video_dl_auto_generated)
    val quality = stringResource(Res.string.video_quality)
    val speed = stringResource(Res.string.video_speed)
    val captions = stringResource(Res.string.video_captions)
    val audio = stringResource(Res.string.video_audio_track)
    val tracks = audioTracks(state.audioTracks)

    @Composable
    fun pick(label: String, selected: Boolean, value: String? = null, onClick: () -> Unit) =
        CosmoActionRow(
            icon = if (selected) CosmoIcons.Check else BlankIcon,
            label = label,
            value = value,
            tint = if (selected) accent else null,
            menu = menu,
            onClick = onClick,
        )

    @Composable
    fun back(label: String) = CosmoActionRow(icon = CosmoIcons.ChevronLeft, label = label, menu = menu) { page = SettingsPage.MAIN }

    // A local file adds an Aspect ratio list of its own to the settings (see LocalSettingsRows).
    val local = LocalVideo.isLocal(state.item?.id)
    var localPage by rememberSaveable { mutableStateOf(LocalSettingsPage.NONE) }
    if (local && localPage == LocalSettingsPage.ASPECT) {
        LocalAspectPage(menu, back = { localPage = LocalSettingsPage.NONE }, done = onDone)
        return
    }

    when (page) {
        SettingsPage.MAIN -> {
            CosmoActionRow(VideoIcons.Hd, quality, value = qualityValue(state), enabled = state.ladder.isNotEmpty(), menu = menu) { page = SettingsPage.QUALITY }
            CosmoActionRow(VideoIcons.Speed, speed, value = speedValue(state.speed), menu = menu) { page = SettingsPage.SPEED }
            CosmoActionRow(
                VideoIcons.Captions, captions,
                value = state.subtitles.firstOrNull { it.language == state.subtitleLanguage }?.name ?: off,
                enabled = state.subtitles.isNotEmpty(), menu = menu,
            ) { page = SettingsPage.CAPTIONS }
            CosmoActionRow(
                CosmoIcons.Translate, audio,
                value = tracks.firstOrNull { it.trackId != null && it.trackId == state.audioTrackId }?.let { trackLabel(it) } ?: original,
                enabled = tracks.size > 1, menu = menu,
            ) { page = SettingsPage.AUDIO }
            val canSmooth = remember(playback) { playback.engine.capabilities.frameSmoothing }
            CosmoActionRow(
                CosmoIcons.Equaliser, stringResource(Res.string.video_smooth_title), value = smoothnessValue(settings.smoothness, canSmooth), menu = menu,
            ) { page = SettingsPage.SMOOTHNESS }
            CosmoActionRow(CosmoIcons.Repeat, stringResource(Res.string.video_loop), value = if (state.loop) on else off, menu = menu) { playback.setLoop(!state.loop) }
            CosmoActionRow(VideoIcons.Info, stringResource(Res.string.video_stats), value = if (statsShown) on else off, menu = menu) { onToggleStats() }
            if (local) LocalSettingsRows(menu, onAspect = { localPage = LocalSettingsPage.ASPECT }, onDone = onDone)
        }
        SettingsPage.QUALITY -> {
            back(quality)
            pick(qualityAutoLabel(state), selected = state.autoQuality) { playback.selectQuality(null); onDone() }
            // The ladder may be short (a video with only 240p and 144p) or empty (live): it lists what there is.
            for (option in state.ladder) {
                pick(option.label, selected = !state.autoQuality && state.quality == option) { playback.selectQuality(option); onDone() }
            }
        }
        SettingsPage.SPEED -> {
            back(speed)
            Box(Modifier.padding(horizontal = SLIDER_PAD_DP.dp)) {
                CosmoSlider(
                    value = (state.speed - SPEED_MIN) / (SPEED_MAX - SPEED_MIN),
                    onValueChange = { playback.setSpeed(snapSpeed(SPEED_MIN + it * (SPEED_MAX - SPEED_MIN))) },
                    contentDescription = speed,
                )
            }
            for (s in SPEEDS) pick(stringResource(Res.string.video_speed_value, speedLabel(s)), selected = state.speed == s) { playback.setSpeed(s); onDone() }
        }
        SettingsPage.CAPTIONS -> {
            back(captions)
            pick(off, selected = state.subtitleLanguage == null) { playback.selectSubtitles(null); onDone() }
            val firstOfLanguage = state.subtitles.indexOfFirst { it.language == state.subtitleLanguage }
            state.subtitles.forEachIndexed { i, track ->
                pick(if (track.autoGenerated) "${track.name} $autoGenerated" else track.name, selected = i == firstOfLanguage) {
                    playback.selectSubtitles(track.language)
                    onDone()
                }
            }
        }
        SettingsPage.SMOOTHNESS -> {
            back(stringResource(Res.string.video_smooth_title))
            SmoothnessPanel(playback, menu)
        }
        SettingsPage.AUDIO -> {
            back(audio)
            pick(original, selected = state.audioTrackId == null) { playback.selectAudioTrack(null); onDone() }
            for (track in tracks) {
                val id = track.trackId ?: continue
                pick(trackLabel(track), selected = state.audioTrackId == id) { playback.selectAudioTrack(id); onDone() }
            }
        }
    }
}

/** "Auto · 1080p60" while Auto plays that rung, "Auto" before it knows, or the rung picked. */
@Composable
private fun qualityValue(state: PlaybackUiState): String =
    if (state.autoQuality) qualityAutoLabel(state) else state.quality?.label ?: stringResource(Res.string.video_quality_auto)

@Composable
private fun qualityAutoLabel(state: PlaybackUiState): String {
    val current = state.quality?.label
    return if (current != null) stringResource(Res.string.video_quality_auto_value, current) else stringResource(Res.string.video_quality_auto)
}

@Composable
private fun speedValue(speed: Float): String = stringResource(Res.string.video_speed_value, speedLabel(speed))

/** The speed as the settings write it before the "x": "1", "1.25", "0.5". */
internal fun speedLabel(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    val whole = hundredths / 100
    val rest = hundredths % 100
    if (rest == 0) return whole.toString()
    val fraction = rest.toString().padStart(2, '0').trimEnd('0')
    return "$whole.$fraction"
}

/** A slider's speed, snapped to 0.05 and kept within 0.25 to 4. */
internal fun snapSpeed(speed: Float): Float = ((speed / SLIDER_SNAP).roundToInt() * SLIDER_SNAP).coerceIn(SPEED_MIN, SPEED_MAX)

/** One entry per audio track (the streams of a track share its id); a video with no ids has one track. */
private fun audioTracks(streams: List<AudioStream>): List<AudioStream> = streams.distinctBy { it.trackId }

/** A track's name: the source's, else its language, else its kind. */
@Composable
private fun trackLabel(track: AudioStream): String = track.trackName ?: track.language ?: stringResource(
    when (track.trackKind) {
        AudioTrackKind.DUBBED -> Res.string.video_audio_dubbed
        AudioTrackKind.DESCRIPTIVE -> Res.string.video_audio_descriptive
        else -> Res.string.video_audio_original
    },
)

/** The empty icon of a list row that is not the picked one (the picked one shows the check, so the labels line up). */
private val BlankIcon: ImageVector = ImageVector.Builder(
    name = "blank", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).build()
