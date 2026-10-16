package com.cosmotune.video.ui.downloads

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.sheets.CosmoSheetGap
import com.cosmotune.ui.sheets.CosmoSheetHeading
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadOptions
import com.cosmotune.video.downloads.DownloadPlanner
import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.RungOption
import com.cosmotune.video.downloads.SubtitleLanguage
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.player.controller.ErrorMapping
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_audio_only
import com.cosmotune.video.resources.video_dl_auto
import com.cosmotune.video.resources.video_dl_auto_means
import com.cosmotune.video.resources.video_dl_button
import com.cosmotune.video.resources.video_dl_button_n
import com.cosmotune.video.resources.video_dl_estimate
import com.cosmotune.video.resources.video_dl_header_batch
import com.cosmotune.video.resources.video_dl_quality
import com.cosmotune.video.resources.video_dl_remember
import com.cosmotune.video.resources.video_dl_subtitle_language
import com.cosmotune.video.resources.video_dl_subtitles_q
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.actions.DownloadAsk
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.video.ui.player.errorMessage
import org.jetbrains.compose.resources.stringResource

/*
 * The download sheet (spec 5.16, plan task Y-32): one C sheet on the phone ([DownloadOptionsSheet], part two's sheet surface: the solid
 * glass with no backdrop blur, `cosmoGlass(hazeState = null, ...)`) and the same content in the desktop's dialog
 * ([DownloadOptionsDialog]). The rows are the sheet row of part two (`.srow`): 54 dp at least, 8 dp in from the sides, radius 18, 14 dp
 * inside, the mark or icon 24 dp and 16 dp before the text, 15.5 sp weight 500; a row's second line 12.5 sp in `textSecondary`; the
 * size at the end 13 sp tabular in `textTertiary`.
 */

/** A row's height (spec 5.16: "radio rows (54 dp)"). */
internal val ROW_HEIGHT = 54.dp
private val ROW_INSET = 8.dp
private val ROW_RADIUS = 18.dp
private val ROW_PADDING = 14.dp
private val ROW_GAP = 16.dp
private val END_GAP = 12.dp
private const val TITLE_SP = 15.5f
private const val TITLE_WEIGHT = 500
private const val DETAIL_SP = 12.5f
private const val SIZE_SP = 13f
private const val STATE_LAYER_ALPHA = 0.12f

/** The mark of a radio row or a checkbox: 24 dp, a 1.5 dp `textTertiary` ring, the accent with a 16 dp check when chosen. */
private val MARK = 24.dp
private val MARK_RING = 1.5.dp
private val MARK_CHECK = 16.dp
private val BOX_INSET = 2.dp
private val BOX_RADIUS = 6.dp
private val ICON = 24.dp

/** The head (`.sh-head`, as the video sheet's): padding 0 20 16, 14 dp gap, the title 17 sp weight 700, the list's name 13.5 sp. */
private val HEAD_PADDING_H = 20.dp
private val HEAD_PADDING_BOTTOM = 16.dp
private val HEAD_GAP = 14.dp
private const val HEAD_TITLE_SP = 17f
private const val HEAD_SUBTITLE_SP = 13.5f

/** The one item's thumbnail in the head (spec 5.16: 96 x 54; radius 12 as the video sheet's head). */
private val HEAD_THUMB_W = 96.dp
private val HEAD_THUMB_H = 54.dp
private val HEAD_THUMB_SHAPE = SuperellipseShape(12.dp)

/** The quality rows scroll past five and a half rows (a full ladder is ten). */
private val QUALITY_MAX_HEIGHT = ROW_HEIGHT * 5.5f

/** Where the options would stand while they load, so the sheet does not jump when they come. */
private val LOADING_HEIGHT = 216.dp

/** The button: the page margin on both sides, 12 dp above, then the room under the last row the other sheets keep. */
private val BUTTON_TOP = 12.dp
private val SHEET_END = 8.dp

/**
 * What the download sheet holds while it is open (spec 5.16), for one [ask]: the options the engine offered (null while they load),
 * the failure that stopped them, and the choices: the quality, the subtitle question and its language, "Remember my choice" and, for
 * a channel's latest uploads, how many. It starts on the remembered choice of [prefs] where the options allow it
 * ([DownloadAskResolver.initialQuality], [DownloadAskResolver.initialLanguage] with [appLanguage]); "Remember my choice" starts ticked
 * when a choice is remembered already (the sheet came back through "Change" or the latest picker). [entries] is the engine's list when
 * the sheet opened: a batch counts what it would still download.
 */
@Stable
internal class DownloadSheetState(
    val ask: DownloadAsk,
    private val prefs: DownloadPrefs,
    private val appLanguage: String,
    private val entries: List<DownloadEntry>,
) {
    var options: DownloadOptions? by mutableStateOf(null)
        private set
    var error: VideoUiError? by mutableStateOf(null)
        private set
    var quality: DownloadQuality by mutableStateOf(prefs.remembered.quality)
    var subtitles: Boolean by mutableStateOf(prefs.remembered.subtitles.enabled)
    var language: SubtitleLanguage? by mutableStateOf(null)
    var remember: Boolean by mutableStateOf(!prefs.askBeforeEach)
    var count: Int by mutableIntStateOf(LatestCount.DEFAULT)

    /** Bumped by "Try again": the host loads the options again. */
    var attempt: Int by mutableIntStateOf(0)
        private set

    private var seeded = false

    /** Whether the sheet asks how many of the newest to take (a channel's "Download the latest"). */
    val picker: Boolean get() = ask is DownloadAsk.Batch && ask.latestPicker

    /** The videos "Download" would queue now: the one video, or the batch's items (the newest [count] with the picker) not yet downloaded or queued. */
    val items: List<VideoItem> by derivedStateOf {
        when (val a = ask) {
            is DownloadAsk.One -> listOf(a.item)
            is DownloadAsk.Batch -> DownloadAskResolver.pending(DownloadAskResolver.batchItems(a, count), entries)
        }
    }

    /** Whether "Download" can be pressed: the options are in and there is something to download. */
    val canDownload: Boolean get() = options != null && items.isNotEmpty()

    /** The options came: the first time the choices start on the remembered ones; later (a new count) they stay where they are when they can. */
    fun loaded(found: DownloadOptions) {
        options = found
        error = null
        if (!seeded) {
            seeded = true
            quality = DownloadAskResolver.initialQuality(found, prefs.remembered.quality)
            language = DownloadAskResolver.initialLanguage(found, prefs.remembered.subtitles, appLanguage)
            return
        }
        quality = DownloadAskResolver.initialQuality(found, quality)
        if (language !in found.subtitleLanguages) language = DownloadAskResolver.initialLanguage(found, prefs.remembered.subtitles, appLanguage)
    }

    /** The options could not be had: the sheet says why, with "Try again". */
    fun failed(cause: Throwable) {
        error = ErrorMapping.map(cause)
    }

    /** "Try again". */
    fun retry() {
        error = null
        attempt += 1
    }

    /** What "Download" asks for. */
    fun request(): DownloadRequest = DownloadAskResolver.request(quality, subtitles, language)
}

/**
 * The phone's download sheet (spec 5.16): part two's sheet host and surface (a C sheet of the solid glass, no backdrop blur), the
 * [DownloadOptionsBody], and the subtitle language list as a second sheet over it. [onConfirm] is "Download"; [onDismiss] closes it
 * (a drag, the scrim, Back).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadOptionsSheet(state: DownloadSheetState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var choosingLanguage by remember { mutableStateOf(false) }
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DownloadOptionsBody(state, onLanguage = { choosingLanguage = true }, onConfirm = onConfirm)
    }
    val options = state.options
    if (choosingLanguage && options != null) {
        SubtitleLanguageSheet(
            languages = options.subtitleLanguages,
            selected = state.language,
            onPick = {
                state.language = it
                choosingLanguage = false
            },
            onDismiss = { choosingLanguage = false },
        )
    }
}

/**
 * What the sheet and the dialog hold (spec 5.16), in order: the head (one item: its 96 x 54 thumbnail and title; a batch: "Download 24
 * videos" and the list's name); for a channel's latest uploads the [LatestCountPicker]; while the options load a [CosmoLoadingBlob]
 * (or why they failed, with "Try again"); "Quality" and its radio rows (Auto with the rung it means, each rung with its codec, audio
 * only; each with its size, "about" for an estimate); "Download subtitles too?" and, when on, the language row ([onLanguage] opens the
 * list); "Remember my choice"; the full-width primary "Download" (or "Download 24"), [onConfirm]. [firstFocus] goes on the chosen
 * quality row (the desktop dialog focuses it, so the arrows start there).
 */
@Composable
internal fun ColumnScope.DownloadOptionsBody(
    state: DownloadSheetState,
    onLanguage: () -> Unit,
    onConfirm: () -> Unit,
    firstFocus: FocusRequester? = null,
) {
    DownloadSheetHead(state)
    if (state.picker) LatestCountPicker(selected = state.count, onSelect = { state.count = it })
    val options = state.options
    val error = state.error
    when {
        options == null && error != null -> OptionsFailed(error, onRetry = state::retry)
        options == null -> Box(Modifier.fillMaxWidth().height(LOADING_HEIGHT), contentAlignment = Alignment.Center) { CosmoLoadingBlob() }
        else -> {
            QualityRows(state, options, firstFocus)
            SubtitleRows(state, options, onLanguage)
            CosmoSheetGap(menu = false)
            DownloadCheckRow(title = stringResource(Res.string.video_dl_remember), checked = state.remember, onCheckedChange = { state.remember = it })
        }
    }
    val label = when (state.ask) {
        is DownloadAsk.One -> stringResource(Res.string.video_dl_button)
        is DownloadAsk.Batch -> stringResource(Res.string.video_dl_button_n, state.items.size)
    }
    Box(Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = BUTTON_TOP)) {
        CosmoButton(
            text = label,
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            kind = CosmoButtonKind.PRIMARY,
            enabled = state.canDownload,
            floating = true,
        )
    }
    Spacer(Modifier.height(SHEET_END))
}

/** The head: one item's thumbnail and title (its DeArrow title and picture when those are on), or the batch's count and name. */
@Composable
private fun DownloadSheetHead(state: DownloadSheetState) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    when (val ask = state.ask) {
        is DownloadAsk.One -> {
            val title = rememberVideoTitle(ask.item)
            val picture = rememberVideoThumbnail(ask.item)
            val style = remember(type, title) { type.forText(headTitleStyle(type.textFamily), title) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = HEAD_PADDING_H, end = HEAD_PADDING_H, bottom = HEAD_PADDING_BOTTOM)
                    .semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThumbnailFrame(url = picture, modifier = Modifier.size(HEAD_THUMB_W, HEAD_THUMB_H), shape = HEAD_THUMB_SHAPE)
                Spacer(Modifier.width(HEAD_GAP))
                Text(text = title, style = style, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        is DownloadAsk.Batch -> {
            val title = stringResource(Res.string.video_dl_header_batch, state.items.size)
            val name = ask.info.title
            val titleStyle = remember(type, title) { type.forText(headTitleStyle(type.textFamily), title) }
            val nameStyle = remember(type, name) {
                type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = HEAD_SUBTITLE_SP.sp, lineHeight = HEAD_SUBTITLE_SP.sp * 1.3f), name)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = HEAD_PADDING_H, end = HEAD_PADDING_H, bottom = HEAD_PADDING_BOTTOM)
                    .semantics(mergeDescendants = true) { heading() },
            ) {
                Text(text = title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (name.isNotBlank()) Text(text = name, style = nameStyle, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun headTitleStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = HEAD_TITLE_SP.sp, lineHeight = HEAD_TITLE_SP.sp * 1.25f)

/**
 * "Quality" and its radio rows: Auto (with the rung it means and that rung's size), each rung with its codec, audio only. A full ladder
 * is ten rows: they scroll inside [QUALITY_MAX_HEIGHT] (five and a half rows, so the cut shows there is more), which keeps the subtitle
 * question, "Remember my choice" and "Download" on the screen; the chosen row is scrolled into the window when the options come.
 */
@Composable
private fun QualityRows(state: DownloadSheetState, options: DownloadOptions, firstFocus: FocusRequester?) {
    CosmoSheetHeading(stringResource(Res.string.video_dl_quality))
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(options) {
        val index = when (val q = state.quality) {
            DownloadQuality.Auto -> 0
            DownloadQuality.AudioOnly -> options.rungs.size + 1
            is DownloadQuality.Rung -> options.rungs.indexOfFirst { it.quality == q } + 1
        }
        // one row of the ones before it stays in sight above the chosen one
        scroll.scrollTo(with(density) { (ROW_HEIGHT * (index - 1).coerceAtLeast(0)).roundToPx() })
    }
    Column(Modifier.fillMaxWidth().heightIn(max = QUALITY_MAX_HEIGHT).verticalScroll(scroll).selectableGroup()) {
        val focusOn = { quality: DownloadQuality -> if (firstFocus != null && state.quality == quality) Modifier.focusRequester(firstFocus) else Modifier }
        DownloadRadioRow(
            title = stringResource(Res.string.video_dl_auto),
            detail = stringResource(Res.string.video_dl_auto_means, options.autoMeans),
            end = sizeLabel(DownloadAskResolver.autoOption(options)),
            selected = state.quality == DownloadQuality.Auto,
            onSelect = { state.quality = DownloadQuality.Auto },
            modifier = focusOn(DownloadQuality.Auto),
        )
        for (rung in options.rungs) {
            key(rung.quality) {
                DownloadRadioRow(
                    title = rung.label,
                    detail = rung.codec,
                    end = sizeLabel(rung),
                    selected = state.quality == rung.quality,
                    onSelect = { state.quality = rung.quality },
                    modifier = focusOn(rung.quality),
                )
            }
        }
        DownloadRadioRow(
            title = stringResource(Res.string.video_dl_audio_only),
            detail = options.audioOnly.codec,
            end = sizeLabel(options.audioOnly),
            selected = state.quality == DownloadQuality.AudioOnly,
            onSelect = { state.quality = DownloadQuality.AudioOnly },
            modifier = focusOn(DownloadQuality.AudioOnly),
        )
    }
}

/** "Download subtitles too?" and, when on, the language row; nothing for a video with no subtitles. */
@Composable
private fun SubtitleRows(state: DownloadSheetState, options: DownloadOptions, onLanguage: () -> Unit) {
    if (options.subtitleLanguages.isEmpty()) return
    CosmoSheetGap(menu = false)
    DownloadSwitchRow(
        icon = VideoIcons.Captions,
        title = stringResource(Res.string.video_dl_subtitles_q),
        checked = state.subtitles,
        onCheckedChange = { state.subtitles = it },
    )
    val language = state.language
    if (state.subtitles && language != null) {
        DownloadNavRow(
            icon = CosmoIcons.Translate,
            title = stringResource(Res.string.video_dl_subtitle_language),
            detail = subtitleLanguageLabel(language),
            onClick = onLanguage,
        )
    }
}

/** The options did not come: the reason in words and "Try again". */
@Composable
private fun OptionsFailed(error: VideoUiError, onRetry: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val message = stringResource(errorMessage(error))
    val style = remember(type, message) {
        type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = TITLE_SP.sp, lineHeight = TITLE_SP.sp * 1.35f, textAlign = TextAlign.Center), message)
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = LOADING_HEIGHT).padding(horizontal = CosmoSpacing.s6, vertical = CosmoSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, style = style, color = Color(palette.textSecondary))
        Spacer(Modifier.height(CosmoSpacing.s3))
        CosmoButton(stringResource(Res.string.video_retry), onRetry, kind = CosmoButtonKind.TONAL, size = CosmoButtonSize.SMALL, floating = true)
    }
}

/** A row's size: "312 MB", or "about 1.4 GB" for an estimate (spec 5.16). */
@Composable
private fun sizeLabel(option: RungOption): String {
    val size = DownloadPlanner.sizeText(option.estimatedBytes)
    return if (option.isEstimate) stringResource(Res.string.video_dl_estimate, size) else size
}

// ---- The rows ------------------------------------------------------------------------------------------------------------------------

/** One choice among several (a quality, a language): the radio mark, the [title] and its [detail], the [end] text; a check when [selected]. */
@Composable
internal fun DownloadRadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    end: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    DownloadSheetRow(
        title = title,
        detail = detail,
        end = end,
        interaction = interaction,
        modifier = modifier,
        onSpace = onSelect,
        action = Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onSelect),
        leading = { ChoiceMark(selected, square = false) },
    )
}

/** A checkbox row ("Remember my choice"): the box mark at the start, the whole row toggles. */
@Composable
internal fun DownloadCheckRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    DownloadSheetRow(
        title = title,
        interaction = interaction,
        onSpace = { onCheckedChange(!checked) },
        action = Modifier.toggleable(value = checked, interactionSource = interaction, indication = null, role = Role.Checkbox, onValueChange = onCheckedChange),
        leading = { ChoiceMark(checked, square = true) },
    )
}

/**
 * A switch row ("Download subtitles too?"): the icon, the title and part two's switch (the C one) at the end; the whole row toggles
 * and is the one control for assistive technology and the keyboard (the switch inside is drawn, not focused).
 */
@Composable
internal fun DownloadSwitchRow(icon: ImageVector, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    DownloadSheetRow(
        title = title,
        interaction = interaction,
        onSpace = { onCheckedChange(!checked) },
        action = Modifier.toggleable(value = checked, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = onCheckedChange),
        leading = { RowIcon(icon) },
        trailing = {
            Box(Modifier.focusProperties { canFocus = false }.clearAndSetSemantics {}) {
                CosmoSwitch(checked = checked, onCheckedChange = onCheckedChange, floating = true)
            }
        },
    )
}

/** A row that opens something (the language list): the icon, the title, what is chosen under it, a chevron. [clickLabel] says what a tap does. */
@Composable
internal fun DownloadNavRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit, clickLabel: String? = null, chevron: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    DownloadSheetRow(
        title = title,
        detail = detail,
        interaction = interaction,
        onSpace = onClick,
        action = Modifier.clickable(interactionSource = interaction, indication = null, onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
        leading = { RowIcon(icon) },
        trailing = if (chevron) ({ RowIcon(CosmoIcons.ChevronRight) }) else null,
    )
}

/**
 * The sheet row every row above is: 54 dp at least, 8 dp in from the sides, radius 18 (its focus ring outside it, the ink's 12 % state
 * layer while pressed or hovered, both read while drawing), 14 dp inside; [leading] 24 dp and 16 dp before the [title] (15.5 sp 500)
 * and its [detail] (12.5 sp `textSecondary`), the [end] text (13 sp tabular `textTertiary`) and [trailing]. [action] is the row's
 * selectable, toggleable or clickable, made with [interaction]; Space does [onSpace] (the desktop's keyboard).
 */
@Composable
private fun DownloadSheetRow(
    title: String,
    interaction: MutableInteractionSource,
    action: Modifier,
    onSpace: () -> Unit,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    end: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val pressed = interaction.collectIsPressedAsState()
    val hovered = interaction.collectIsHoveredAsState()
    val shape = remember { RoundedCornerShape(ROW_RADIUS) }
    val titleStyle = remember(type, title) {
        type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(TITLE_WEIGHT), fontSize = TITLE_SP.sp, lineHeight = TITLE_SP.sp * 1.3f), title)
    }
    val detailStyle = remember(type, detail) {
        type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = DETAIL_SP.sp, lineHeight = DETAIL_SP.sp * 1.3f), detail.orEmpty())
    }
    val endStyle = remember(type) {
        TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = SIZE_SP.sp, lineHeight = SIZE_SP.sp * 1.3f, fontFeatureSettings = "tnum")
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_INSET)
            // the ring is drawn outside the row, so it comes before the clip
            .cosmoFocusRing(interaction, ROW_RADIUS, floating = true)
            .clip(shape)
            .drawBehind {
                if (pressed.value || hovered.value) drawRect(colors.live(CoverField.TEXT_PRIMARY).let { it.copy(alpha = it.alpha * STATE_LAYER_ALPHA) })
            }
            .hoverable(interaction)
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Spacebar) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) onSpace()
                true
            }
            .then(action)
            .heightIn(min = ROW_HEIGHT)
            .padding(horizontal = ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(ROW_GAP))
        Column(Modifier.weight(1f).padding(vertical = CosmoSpacing.s2)) {
            Text(text = title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) {
                Text(text = detail, style = detailStyle, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (end != null) {
            Spacer(Modifier.width(END_GAP))
            Text(text = end, style = endStyle, color = Color(palette.textTertiary), maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(END_GAP))
            trailing()
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, tint = Color(LocalCosmoColors.current.palette.textSecondary), modifier = Modifier.size(ICON))
}

/**
 * The mark of a choice: a ring (a circle for a radio row, a rounded box for a checkbox) in `textTertiary` that fills with the accent and
 * shows a check in `onAccent` when [chosen]; the fade runs on the effects spring (reduced motion: a short fade) and is read while drawing.
 */
@Composable
private fun ChoiceMark(chosen: Boolean, square: Boolean) {
    val colors = LocalCosmoColors.current
    val reduced = LocalReducedMotion.current
    val fill = animateFloatAsState(if (chosen) 1f else 0f, CosmoMotion.effects(reduced), label = "download choice")
    Box(
        Modifier
            .size(MARK)
            .drawBehind {
                val amount = fill.value.coerceIn(0f, 1f)
                val ring = MARK_RING.toPx()
                val inset = if (square) BOX_INSET.toPx() else 0f
                val corner = CornerRadius(BOX_RADIUS.toPx())
                if (amount < 1f) {
                    val line = colors.live(CoverField.TEXT_TERTIARY).let { it.copy(alpha = it.alpha * (1f - amount)) }
                    if (square) {
                        drawRoundRect(line, topLeft = Offset(inset + ring / 2f, inset + ring / 2f), size = Size(size.width - 2 * inset - ring, size.height - 2 * inset - ring), cornerRadius = corner, style = Stroke(ring))
                    } else {
                        drawCircle(line, radius = (size.minDimension - ring) / 2f, style = Stroke(ring))
                    }
                }
                if (amount > 0f) {
                    val accent = colors.live(CoverField.ACCENT).let { it.copy(alpha = it.alpha * amount) }
                    if (square) {
                        drawRoundRect(accent, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset), cornerRadius = corner)
                    } else {
                        drawCircle(accent, radius = size.minDimension / 2f)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CosmoIcons.Check,
            contentDescription = null,
            tint = Color(colors.palette.onAccent),
            modifier = Modifier.size(MARK_CHECK).graphicsLayer { alpha = fill.value.coerceIn(0f, 1f) },
        )
    }
}
