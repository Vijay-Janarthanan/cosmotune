package com.cosmotune.video.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.settings.CosmoRowDivider
import com.cosmotune.ui.settings.CosmoSettingsGroup
import com.cosmotune.ui.settings.CosmoSettingsInsets
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.settings.CosmoSettingsSegmented
import com.cosmotune.ui.settings.CosmoSettingsSubRow
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.player.rememberPictureInPicture
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_case_as_given
import com.cosmotune.video.resources.video_case_sentence
import com.cosmotune.video.resources.video_case_title
import com.cosmotune.video.resources.video_clear
import com.cosmotune.video.resources.video_clear_history
import com.cosmotune.video.resources.video_clear_history_confirm
import com.cosmotune.video.resources.video_disclaimer_text
import com.cosmotune.video.resources.video_disclaimer_title
import com.cosmotune.video.resources.video_dl_audio_only
import com.cosmotune.video.resources.video_dl_each_original
import com.cosmotune.video.resources.video_dl_subs_got
import com.cosmotune.video.resources.video_dl_subs_none
import com.cosmotune.video.resources.video_drawer_settings
import com.cosmotune.video.resources.video_history_paused
import com.cosmotune.video.resources.video_meta_join
import com.cosmotune.video.resources.video_off
import com.cosmotune.video.resources.video_pip
import com.cosmotune.video.resources.video_pause_history
import com.cosmotune.video.resources.video_quality_auto
import com.cosmotune.video.resources.video_set_about
import com.cosmotune.video.resources.video_set_autoplay
import com.cosmotune.video.resources.video_set_background_short
import com.cosmotune.video.resources.video_set_captions
import com.cosmotune.video.resources.video_set_captions_background_short
import com.cosmotune.video.resources.video_set_captions_language
import com.cosmotune.video.resources.video_set_captions_size
import com.cosmotune.video.resources.video_set_clear_search
import com.cosmotune.video.resources.video_set_clear_search_confirm
import com.cosmotune.video.resources.video_set_codec
import com.cosmotune.video.resources.video_set_contacts
import com.cosmotune.video.resources.video_set_contacts_text
import com.cosmotune.video.resources.video_set_content
import com.cosmotune.video.resources.video_set_dearrow
import com.cosmotune.video.resources.video_set_dearrow_thumbs_short
import com.cosmotune.video.resources.video_set_dearrow_titles_short
import com.cosmotune.video.resources.video_set_device
import com.cosmotune.video.resources.video_set_dislikes
import com.cosmotune.video.resources.video_set_dislikes_credit
import com.cosmotune.video.resources.video_set_dislikes_show
import com.cosmotune.video.resources.video_set_dl_ask_short
import com.cosmotune.video.resources.video_set_dl_battery_short
import com.cosmotune.video.resources.video_set_dl_folder
import com.cosmotune.video.resources.video_set_dl_group
import com.cosmotune.video.resources.video_set_dl_reset
import com.cosmotune.video.resources.video_set_dl_summary
import com.cosmotune.video.resources.video_set_dl_wifi
import com.cosmotune.video.resources.video_set_double_tap
import com.cosmotune.video.resources.video_set_export
import com.cosmotune.video.resources.video_set_hide_shorts
import com.cosmotune.video.resources.video_set_import
import com.cosmotune.video.resources.video_set_language
import com.cosmotune.video.resources.video_set_library
import com.cosmotune.video.resources.video_set_pip_when
import com.cosmotune.video.resources.video_set_playback
import com.cosmotune.video.resources.video_set_quality_mobile
import com.cosmotune.video.resources.video_set_quality_wifi
import com.cosmotune.video.resources.video_set_region
import com.cosmotune.video.resources.video_set_resume_short
import com.cosmotune.video.resources.video_set_seconds
import com.cosmotune.video.resources.video_set_speed
import com.cosmotune.video.resources.video_set_title_case
import com.cosmotune.video.resources.video_set_using_dearrow
import com.cosmotune.video.resources.video_size_large
import com.cosmotune.video.resources.video_size_medium
import com.cosmotune.video.resources.video_size_small
import com.cosmotune.video.resources.video_speed_value
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.settings.CodecPreference
import com.cosmotune.video.settings.TitleCase
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.player.SPEEDS
import com.cosmotune.video.ui.player.speedLabel
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.abs

/** How a row changes the settings: the page hands every row `graph.settings.update`. */
internal typealias SettingsUpdate = ((VideoSettings) -> VideoSettings) -> Unit

/** The choices that open a [ChoiceSheet] (a category of SponsorBlock opens its own, see [CategoryChoiceSheet]). */
private enum class SettingsChoice { WIFI_QUALITY, MOBILE_QUALITY, SPEED, DOUBLE_TAP, CAPTIONS_LANGUAGE, TITLE_CASE, REGION, CONTENT_LANGUAGE }

/** What the page asks about before it clears something. */
private enum class Confirm { CLEAR_HISTORY, CLEAR_SEARCHES }

/**
 * Video settings (spec 5.11): the groups Playback, Captions, SponsorBlock, Titles and thumbnails, Dislikes, Content, Library,
 * Downloads and About, each row bound to a field of the graph's `VideoSettings` (the changes are saved by the store at once) or to
 * the library. Choices open a [ChoiceSheet] (radio rows), a switch is a row with a switch at its end or a sub-row under the option
 * it belongs to, the codec and the size of the captions are segmented, and the three attributions (SponsorBlock, DeArrow, Return
 * YouTube Dislike) are links under their groups.
 *
 * The page scrolls under the shell's bars, so [contentPadding] is on the scrolling content; a [wide] page (the desktop) keeps its
 * groups to a readable width in the middle. The first-run notice's flag and Home's kiosk are kept by the notice and by Home: they
 * have no row.
 */
@Composable
fun VideoSettingsPage(contentPadding: PaddingValues, wide: Boolean) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val settings by graph.settings.settings.collectAsState()
    val historyPaused by graph.library.historyPaused.collectAsState()
    val platform = rememberSettingsPlatform()
    val engine = graph.playback.engine
    val canSmooth = remember(engine) { engine.capabilities.frameSmoothing }
    val smoothReadout by engine.smoothness.collectAsState()
    val pip = rememberPictureInPicture()
    val update: SettingsUpdate = remember(graph) { { transform -> graph.settings.update(transform) } }
    val locale = remember { Locale.getDefault() }
    var choice by remember { mutableStateOf<SettingsChoice?>(null) }
    var category by remember { mutableStateOf<SegmentCategory?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = if (wide) WIDE_MAX_WIDTH else Dp.Unspecified)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            CosmoPageHeader(title = stringResource(Res.string.video_drawer_settings), large = wide)
            PlaybackSettings(settings, update, pip.supported) { choice = it }
            SmoothnessSettingsGroup(settings.smoothness, update, canSmooth, smoothReadout)
            CaptionSettings(settings, update, locale) { choice = it }
            SponsorBlockSettings(settings, update) { category = it }
            TitleSettings(settings, update) { choice = it }
            DislikeSettings(settings, update)
            ContentSettings(settings, update, locale) { choice = it }
            LibrarySettings(historyPaused, graph.library::setHistoryPaused, { confirm = it }, { navigator.push(VideoRoute.Import) })
            DownloadSettings(settings, update, platform, locale)
            AboutSettings()
            Spacer(Modifier.height(PAGE_END))
        }
    }

    choice?.let { SettingsChoiceSheet(it, settings, update, locale) { choice = null } }
    category?.let { CategoryChoiceSheet(it, settings, update) { category = null } }
    when (confirm) {
        Confirm.CLEAR_HISTORY -> SettingsConfirmDialog(
            title = stringResource(Res.string.video_clear_history),
            message = stringResource(Res.string.video_clear_history_confirm),
            confirmLabel = stringResource(Res.string.video_clear),
            onConfirm = { graph.library.clearHistory() },
            onDismiss = { confirm = null },
        )
        Confirm.CLEAR_SEARCHES -> SettingsConfirmDialog(
            title = stringResource(Res.string.video_set_clear_search),
            message = stringResource(Res.string.video_set_clear_search_confirm),
            confirmLabel = stringResource(Res.string.video_clear),
            onConfirm = { graph.library.clearSearches() },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

// ---- Playback ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun PlaybackSettings(settings: VideoSettings, update: SettingsUpdate, pipSupported: Boolean, open: (SettingsChoice) -> Unit) {
    val auto = stringResource(Res.string.video_quality_auto)
    val codecs = CodecPreference.entries.map { codecLabel(it) }
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_playback)) {
        CosmoSettingsRow(
            icon = VideoIcons.Hd,
            title = stringResource(Res.string.video_set_quality_wifi),
            subtitle = qualityLabel(settings.wifiQuality, auto),
            onClick = { open(SettingsChoice.WIFI_QUALITY) },
        )
        CosmoSettingsRow(
            icon = CosmoIcons.Cellular,
            title = stringResource(Res.string.video_set_quality_mobile),
            subtitle = qualityLabel(settings.meteredQuality, auto),
            onClick = { open(SettingsChoice.MOBILE_QUALITY) },
        )
        CosmoSettingsRow(icon = CosmoIcons.Video, title = stringResource(Res.string.video_set_codec))
        CosmoSettingsSegmented(
            options = codecs,
            selectedIndex = settings.codec.ordinal,
            onSelect = { index -> update { it.copy(codec = CodecPreference.entries[index]) } },
            modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = SEGMENT_BOTTOM),
            compact = true,
        )
        CosmoRowDivider()
        CosmoSettingsRow(
            icon = VideoIcons.Speed,
            title = stringResource(Res.string.video_set_speed),
            subtitle = stringResource(Res.string.video_speed_value, speedLabel(settings.defaultSpeed)),
            onClick = { open(SettingsChoice.SPEED) },
        )
        CosmoSettingsRow(
            icon = VideoIcons.Forward,
            title = stringResource(Res.string.video_set_double_tap),
            subtitle = stringResource(Res.string.video_set_seconds, settings.doubleTapSeconds),
            onClick = { open(SettingsChoice.DOUBLE_TAP) },
        )
        CosmoRowDivider()
        SwitchRow(CosmoIcons.Next, stringResource(Res.string.video_set_autoplay), settings.autoplay) { value -> update { it.copy(autoplay = value) } }
        SwitchRow(VideoIcons.History, stringResource(Res.string.video_set_resume_short), settings.resume) { value -> update { it.copy(resume = value) } }
        SwitchRow(CosmoIcons.Volume, stringResource(Res.string.video_set_background_short), settings.background) { value -> update { it.copy(background = value) } }
        // Picture in picture is the phone's: the desktop (and a phone without it) is not asked.
        if (pipSupported) {
            SwitchRow(VideoIcons.Pip, stringResource(Res.string.video_pip), settings.autoPip, subtitle = stringResource(Res.string.video_set_pip_when)) { value -> update { it.copy(autoPip = value) } }
        }
    }
}

// ---- Captions ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun CaptionSettings(settings: VideoSettings, update: SettingsUpdate, locale: Locale, open: (SettingsChoice) -> Unit) {
    val sizes = listOf(
        stringResource(Res.string.video_size_small),
        stringResource(Res.string.video_size_medium),
        stringResource(Res.string.video_size_large),
    )
    val off = stringResource(Res.string.video_off)
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_captions)) {
        CosmoSettingsRow(
            icon = VideoIcons.Captions,
            title = stringResource(Res.string.video_set_captions_language),
            subtitle = settings.captionsLanguage?.let { languageName(it, locale) } ?: off,
            onClick = { open(SettingsChoice.CAPTIONS_LANGUAGE) },
        )
        CosmoRowDivider()
        CosmoSettingsRow(icon = CosmoIcons.Expand, title = stringResource(Res.string.video_set_captions_size))
        CosmoSettingsSegmented(
            options = sizes,
            selectedIndex = nearestScale(settings.captionsScale),
            onSelect = { index -> update { it.copy(captionsScale = VideoSettings.CAPTION_SCALES[index]) } },
            modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = SEGMENT_BOTTOM),
            compact = true,
        )
        CosmoSettingsSubRow(
            title = stringResource(Res.string.video_set_captions_background_short),
            checked = settings.captionsBackground,
            onCheckedChange = { value -> update { it.copy(captionsBackground = value) } },
        )
    }
}

/** The index in [VideoSettings.CAPTION_SCALES] nearest to [scale], so a stored size between two still shows one chosen. */
internal fun nearestScale(scale: Float): Int =
    VideoSettings.CAPTION_SCALES.indices.minBy { abs(VideoSettings.CAPTION_SCALES[it] - scale) }

// ---- Titles and thumbnails, dislikes -------------------------------------------------------------------------------------------------

@Composable
private fun TitleSettings(settings: VideoSettings, update: SettingsUpdate, open: (SettingsChoice) -> Unit) {
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_dearrow)) {
        SwitchRow(CosmoIcons.List, stringResource(Res.string.video_set_dearrow_titles_short), settings.deArrowTitles) { value -> update { it.copy(deArrowTitles = value) } }
        SwitchRow(CosmoIcons.Grid, stringResource(Res.string.video_set_dearrow_thumbs_short), settings.deArrowThumbnails) { value -> update { it.copy(deArrowThumbnails = value) } }
        CosmoSettingsRow(
            icon = CosmoIcons.Sort,
            title = stringResource(Res.string.video_set_title_case),
            subtitle = titleCaseLabel(settings.titleCase),
            enabled = settings.deArrowTitles,
            onClick = { open(SettingsChoice.TITLE_CASE) },
        )
    }
    AttributionLink(text = stringResource(Res.string.video_set_using_dearrow), url = DEARROW_URL)
}

@Composable
private fun DislikeSettings(settings: VideoSettings, update: SettingsUpdate) {
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_dislikes)) {
        SwitchRow(VideoIcons.ThumbDown, stringResource(Res.string.video_set_dislikes_show), settings.dislikes) { value -> update { it.copy(dislikes = value) } }
    }
    AttributionLink(text = stringResource(Res.string.video_set_dislikes_credit), url = DISLIKES_URL)
}

// ---- Content and library ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ContentSettings(settings: VideoSettings, update: SettingsUpdate, locale: Locale, open: (SettingsChoice) -> Unit) {
    val device = stringResource(Res.string.video_set_device)
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_content)) {
        SwitchRow(VideoIcons.Shorts, stringResource(Res.string.video_set_hide_shorts), settings.hideShorts) { value -> update { it.copy(hideShorts = value) } }
        CosmoRowDivider()
        CosmoSettingsRow(
            icon = CosmoIcons.Pin,
            title = stringResource(Res.string.video_set_region),
            subtitle = settings.region?.let { regionName(it, locale) } ?: device,
            onClick = { open(SettingsChoice.REGION) },
        )
        CosmoSettingsRow(
            icon = CosmoIcons.Translate,
            title = stringResource(Res.string.video_set_language),
            subtitle = settings.contentLanguage?.let { languageName(it, locale) } ?: device,
            onClick = { open(SettingsChoice.CONTENT_LANGUAGE) },
        )
    }
}

@Composable
private fun LibrarySettings(historyPaused: Boolean, setHistoryPaused: (Boolean) -> Unit, ask: (Confirm) -> Unit, openImport: () -> Unit) {
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_library)) {
        SwitchRow(
            icon = CosmoIcons.Pause,
            title = stringResource(Res.string.video_pause_history),
            checked = historyPaused,
            subtitle = stringResource(Res.string.video_history_paused).takeIf { historyPaused },
            onChange = setHistoryPaused,
        )
        CosmoSettingsRow(icon = CosmoIcons.Close, title = stringResource(Res.string.video_clear_history), onClick = { ask(Confirm.CLEAR_HISTORY) })
        CosmoSettingsRow(icon = CosmoIcons.Search, title = stringResource(Res.string.video_set_clear_search), onClick = { ask(Confirm.CLEAR_SEARCHES) })
        CosmoRowDivider()
        // Export lives on the Import page, with the importers.
        CosmoSettingsRow(icon = VideoIcons.Upload, title = stringResource(Res.string.video_set_import), onClick = openImport)
        CosmoSettingsRow(icon = CosmoIcons.Download, title = stringResource(Res.string.video_set_export), onClick = openImport)
    }
}

// ---- Downloads ---------------------------------------------------------------------------------------------------------------------------

@Composable
private fun DownloadSettings(settings: VideoSettings, update: SettingsUpdate, platform: SettingsPlatform, locale: Locale) {
    val prefs = settings.downloads
    val language = downloadSubtitleLanguage(prefs.remembered.subtitles, stringResource(Res.string.video_dl_each_original), locale)
    val subtitles = if (language == null) stringResource(Res.string.video_dl_subs_none) else stringResource(Res.string.video_dl_subs_got, language)
    val quality = downloadQualityText(prefs.remembered.quality, stringResource(Res.string.video_quality_auto), stringResource(Res.string.video_dl_audio_only))
    val folderTitle = stringResource(Res.string.video_set_dl_folder)
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_dl_group)) {
        // The switch changes only itself: the remembered request stays, so turning it off and on again asks with the same default.
        SwitchRow(CosmoIcons.Download, stringResource(Res.string.video_set_dl_ask_short), settings.downloads.askBeforeEach) { value ->
            update { it.copy(downloads = it.downloads.copy(askBeforeEach = value)) }
        }
        SettingsNote(stringResource(Res.string.video_set_dl_summary, stringResource(Res.string.video_meta_join, quality, subtitles)))
        CosmoSettingsRow(
            icon = VideoIcons.History,
            title = stringResource(Res.string.video_set_dl_reset),
            enabled = prefs != DownloadPrefs(),
            onClick = { update { it.copy(downloads = DownloadPrefs()) } },
        )
        CosmoRowDivider()
        // Only on Wi-Fi and the battery hold are the phone's; the folder is the desktop's.
        if (platform.hasMeteredNetwork) {
            SwitchRow(CosmoIcons.Cellular, stringResource(Res.string.video_set_dl_wifi), settings.downloadWifiOnly) { value -> update { it.copy(downloadWifiOnly = value) } }
            SwitchRow(CosmoIcons.Timer, stringResource(Res.string.video_set_dl_battery_short), settings.pauseOnLowBattery) { value -> update { it.copy(pauseOnLowBattery = value) } }
        }
        if (platform.canChooseFolder) {
            CosmoSettingsRow(
                icon = CosmoIcons.Library,
                title = folderTitle,
                subtitle = settings.downloadFolder ?: platform.defaultFolder,
                onClick = { platform.chooseFolder(folderTitle, settings.downloadFolder) { path -> update { it.copy(downloadFolder = path) } } },
            )
        }
    }
}

/**
 * The words for the language of a remembered subtitle choice, or null when subtitles are off: the language's name, or
 * [eachOriginal] ("Each video's original language") for a choice that follows each video (a null or empty tag).
 */
internal fun downloadSubtitleLanguage(pick: SubtitlePick, eachOriginal: String, inLocale: Locale): String? {
    if (!pick.enabled) return null
    val tag = pick.language
    return if (tag.isNullOrEmpty()) eachOriginal else languageName(tag, inLocale)
}

/** The remembered quality in words: [auto], [audioOnly], or the rung as the ladder writes it ("720p", "1080p60"). */
internal fun downloadQualityText(quality: DownloadQuality, auto: String, audioOnly: String): String = when (quality) {
    DownloadQuality.Auto -> auto
    DownloadQuality.AudioOnly -> audioOnly
    is DownloadQuality.Rung -> QualityOption.labelOf(quality.height, quality.fps, hdr = false)
}

// ---- About ---------------------------------------------------------------------------------------------------------------------------------

@Composable
private fun AboutSettings() {
    CosmoSettingsGroup(header = stringResource(Res.string.video_set_about)) {
        CosmoSettingsRow(
            title = stringResource(Res.string.video_disclaimer_title),
            subtitleContent = { Paragraph(stringResource(Res.string.video_disclaimer_text)) },
        )
        CosmoRowDivider()
        CosmoSettingsRow(
            title = stringResource(Res.string.video_set_contacts),
            subtitleContent = { Paragraph(stringResource(Res.string.video_set_contacts_text)) },
        )
    }
}

// ---- The sheets --------------------------------------------------------------------------------------------------------------------------

/** The list that [choice] opens: the options, the one ticked, and what a pick writes. */
@Composable
private fun SettingsChoiceSheet(choice: SettingsChoice, settings: VideoSettings, update: SettingsUpdate, locale: Locale, onDismiss: () -> Unit) {
    when (choice) {
        SettingsChoice.WIFI_QUALITY -> QualitySheet(stringResource(Res.string.video_set_quality_wifi), settings.wifiQuality, { value -> update { it.copy(wifiQuality = value) } }, onDismiss)
        SettingsChoice.MOBILE_QUALITY -> QualitySheet(stringResource(Res.string.video_set_quality_mobile), settings.meteredQuality, { value -> update { it.copy(meteredQuality = value) } }, onDismiss)
        SettingsChoice.SPEED -> ChoiceSheet(
            title = stringResource(Res.string.video_set_speed),
            options = SPEEDS.map { ChoiceOption(it, stringResource(Res.string.video_speed_value, speedLabel(it))) },
            selected = settings.defaultSpeed,
            onSelect = { value -> update { it.copy(defaultSpeed = value) } },
            onDismiss = onDismiss,
        )
        SettingsChoice.DOUBLE_TAP -> ChoiceSheet(
            title = stringResource(Res.string.video_set_double_tap),
            options = VideoSettings.DOUBLE_TAP_CHOICES.map { ChoiceOption(it, stringResource(Res.string.video_set_seconds, it)) },
            selected = settings.doubleTapSeconds,
            onSelect = { value -> update { it.copy(doubleTapSeconds = value) } },
            onDismiss = onDismiss,
        )
        SettingsChoice.CAPTIONS_LANGUAGE -> {
            val off = stringResource(Res.string.video_off)
            val options = remember(locale, off) { languageChoices(locale, off).map { ChoiceOption(it.code, it.name) } }
            ChoiceSheet(
                title = stringResource(Res.string.video_set_captions_language),
                options = options,
                selected = settings.captionsLanguage?.lowercase(),
                onSelect = { value -> update { it.copy(captionsLanguage = value) } },
                onDismiss = onDismiss,
            )
        }
        SettingsChoice.TITLE_CASE -> ChoiceSheet(
            title = stringResource(Res.string.video_set_title_case),
            options = TitleCase.entries.map { ChoiceOption(it, titleCaseLabel(it)) },
            selected = settings.titleCase,
            onSelect = { value -> update { it.copy(titleCase = value) } },
            onDismiss = onDismiss,
        )
        SettingsChoice.REGION -> {
            val device = stringResource(Res.string.video_set_device)
            val options = remember(locale, device) { regionChoices(locale, device).map { ChoiceOption(it.code, it.name) } }
            ChoiceSheet(
                title = stringResource(Res.string.video_set_region),
                options = options,
                selected = settings.region?.uppercase(),
                onSelect = { value -> update { it.copy(region = value) } },
                onDismiss = onDismiss,
            )
        }
        SettingsChoice.CONTENT_LANGUAGE -> {
            val device = stringResource(Res.string.video_set_device)
            val options = remember(locale, device) { languageChoices(locale, device).map { ChoiceOption(it.code, it.name) } }
            ChoiceSheet(
                title = stringResource(Res.string.video_set_language),
                options = options,
                selected = settings.contentLanguage?.lowercase(),
                onSelect = { value -> update { it.copy(contentLanguage = value) } },
                onDismiss = onDismiss,
            )
        }
    }
}

/** The quality list of Wi-Fi and of mobile data: Auto and every rung from 2160p down to 144p (spec 5.11), [selected] null being Auto. */
@Composable
private fun QualitySheet(title: String, selected: Int?, onSelect: (Int?) -> Unit, onDismiss: () -> Unit) {
    val auto = stringResource(Res.string.video_quality_auto)
    val options = remember(auto) { VideoSettings.QUALITY_CHOICES.map { ChoiceOption(it, qualityLabel(it, auto)) } }
    ChoiceSheet(title = title, options = options, selected = selected, onSelect = onSelect, onDismiss = onDismiss)
}

// ---- Small pieces --------------------------------------------------------------------------------------------------------------------------

/** "Auto" for no height, else "720p". */
internal fun qualityLabel(height: Int?, auto: String): String = if (height == null) auto else "${height}p"

@Composable
private fun codecLabel(codec: CodecPreference): String = when (codec) {
    CodecPreference.AUTO -> stringResource(Res.string.video_quality_auto)
    // The codecs' own names, as the download sheet writes them.
    CodecPreference.AV1 -> com.cosmotune.video.downloads.DownloadPlanner.codecName(com.cosmotune.video.model.VideoCodec.AV1)
    CodecPreference.VP9 -> com.cosmotune.video.downloads.DownloadPlanner.codecName(com.cosmotune.video.model.VideoCodec.VP9)
    CodecPreference.H264 -> com.cosmotune.video.downloads.DownloadPlanner.codecName(com.cosmotune.video.model.VideoCodec.H264)
}

@Composable
private fun titleCaseLabel(case: TitleCase): String = stringResource(
    when (case) {
        TitleCase.AS_GIVEN -> Res.string.video_case_as_given
        TitleCase.SENTENCE -> Res.string.video_case_sentence
        TitleCase.TITLE -> Res.string.video_case_title
    },
)

/** A row whose whole face is a switch at its end (the option stands alone): the lead tile, the title, [subtitle] and the switch. */
@Composable
private fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    CosmoSettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = { CosmoSwitch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/** A line of explanation in a group that is no row of its own: 13 sp in `textSecondary`, under the titles (70 dp from the start). */
@Composable
private fun SettingsNote(text: String) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        text = text,
        style = type.forText(type.body(TypeScale.BODY).copy(fontSize = NOTE_SP.sp, lineHeight = (NOTE_SP * NOTE_LINE).sp), text),
        color = Color(palette.textSecondary),
        modifier = Modifier.fillMaxWidth().padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = NOTE_BOTTOM),
    )
}

/** A paragraph under a row's title (the notice, what Video mode contacts): the row's subtitle look, without its five-line limit. */
@Composable
private fun Paragraph(text: String) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        text = text,
        style = type.forText(type.body(TypeScale.BODY).copy(fontSize = NOTE_SP.sp, lineHeight = (NOTE_SP * NOTE_LINE).sp), text),
        color = Color(palette.textSecondary),
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * An attribution under its group (spec 5.11, 7): [text] as a text button that opens [url] in the system's browser. Nothing is
 * fetched from it by the app; the button is a link, announced as a button with its label, with a 48 dp target.
 */
@Composable
internal fun AttributionLink(text: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Box(Modifier.fillMaxWidth().padding(start = LINK_START)) {
        CosmoButton(
            text = text,
            onClick = {
                // No browser to hand it to: nothing happens.
                try {
                    uriHandler.openUri(url)
                } catch (e: Exception) {
                    // ActivityNotFoundException, IllegalArgumentException, an unsupported desktop
                }
            },
            kind = CosmoButtonKind.TEXT,
            size = CosmoButtonSize.SMALL,
        )
    }
}

/** Where "(Using DeArrow)" leads. */
internal const val DEARROW_URL = "https://dearrow.ajay.app/"

/** Where "Counts from returnyoutubedislike.com" leads (its required attribution). */
internal const val DISLIKES_URL = "https://returnyoutubedislike.com/"

/** A wide page keeps its groups to this width, in the middle. */
private val WIDE_MAX_WIDTH = 760.dp
private val PAGE_END = 28.dp
private val SEGMENT_BOTTOM = 14.dp
private val LINK_START = 8.dp
private val NOTE_BOTTOM = 12.dp
private const val NOTE_SP = 13f
private const val NOTE_LINE = 1.35f
