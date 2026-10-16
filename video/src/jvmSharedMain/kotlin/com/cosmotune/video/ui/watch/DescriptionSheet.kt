package com.cosmotune.video.ui.watch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.Chapter
import com.cosmotune.video.model.Description
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_chapters
import com.cosmotune.video.resources.video_description
import com.cosmotune.video.resources.video_original_title
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.rememberTitleStyle
import com.cosmotune.video.ui.blocks.videoClickable
import com.music.bitchord.sharedui.resources.cosmo_close
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.Res as SharedRes

/*
 * The description sheet (spec 5.4: "the full text with tappable timestamps and links, then the chapters as rows with a 96 x 54
 * frame"). It stands where the comments sheet stands (spec 5.9): a C sheet (detached 8 dp, superellipse 36, solid glass) whose top
 * sits at the bottom of the player, so the video keeps playing above it while a timestamp or a chapter is tried. The caller places
 * it with the modifier and closes it on Back.
 */

private val SHEET_SHAPE = SuperellipseShape(CosmoRadii.sheet)
private val HEADER_PAD_START = 20.dp
private val HEADER_PAD_END = 8.dp
private val HEADER_PAD_V = 4.dp
private val CLOSE_BUTTON = 40.dp
private val BODY_GAP = 8.dp
private val ORIGINAL_GAP = 4.dp
private const val META_SP = 13f
private val TEXT_TOP = 12.dp
private val SHEET_END = 16.dp

/** A chapter's frame (spec 5.4: 96 x 54; radius 12, as the video sheet's header picture). */
private val CHAPTER_FRAME_W = 96.dp
private val CHAPTER_FRAME_H = 54.dp
private val CHAPTER_FRAME_SHAPE = SuperellipseShape(12.dp)
private const val CHAPTER_TITLE_SP = 15f
private const val CHAPTER_TIME_SP = 12.5f

/**
 * The sheet: "Description" and a close button at its head; under it, scrolling, the [title], the [originalTitle] when DeArrow
 * replaced it, the [meta] line, the full [description] (its timestamps call [onSeek], its links open the browser) and the
 * [chapters] as rows that seek. [onDismiss] is the close button.
 */
@Composable
internal fun DescriptionSheet(
    title: String,
    originalTitle: String?,
    meta: String,
    description: Description,
    chapters: List<Chapter>,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val heading = stringResource(Res.string.video_description)
    Column(
        modifier
            .fillMaxSize()
            .padding(start = CosmoRadii.sheetInset, end = CosmoRadii.sheetInset, top = CosmoRadii.sheetInset, bottom = CosmoRadii.sheetInset)
            .cosmoGlass(hazeState = null, shape = SHEET_SHAPE),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = HEADER_PAD_START, end = HEADER_PAD_END, top = HEADER_PAD_V, bottom = HEADER_PAD_V),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                heading,
                style = type.forText(type.title(TypeScale.TITLE_S), heading),
                color = Color(palette.textPrimary),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            CosmoIconButton(
                icon = CosmoIcons.Close,
                contentDescription = stringResource(SharedRes.string.cosmo_close),
                onClick = onDismiss,
                style = CosmoIconButtonStyle.PLAIN,
                size = CLOSE_BUTTON,
                floating = true,
            )
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = BODY_GAP, bottom = SHEET_END)) {
            DescriptionBody(title, originalTitle, meta, description, onSeek)
            if (chapters.isNotEmpty()) {
                CosmoOverline(stringResource(Res.string.video_chapters))
                for (chapter in chapters) ChapterRow(chapter, onSeek)
            }
        }
    }
}

/** The sheet's text: the title, the original title, the meta line and the whole description with its links. */
@Composable
private fun DescriptionBody(title: String, originalTitle: String?, meta: String, description: Description, onSeek: (Long) -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val titleStyle = remember(type, title) { type.forText(type.title(TypeScale.TITLE_S), title) }
    val metaStyle = rememberMetaStyle(META_SP, meta)
    val textStyle = remember(type, description.text) { type.descriptionStyle(description.text) }
    val text = rememberDescriptionText(description, onSeek)
    Column(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin)) {
        Text(title, style = titleStyle, color = Color(palette.textPrimary))
        if (originalTitle != null) {
            val line = stringResource(Res.string.video_original_title) + ": " + originalTitle
            Text(line, style = rememberMetaStyle(META_SP, line), color = Color(palette.textTertiary), modifier = Modifier.padding(top = ORIGINAL_GAP))
        }
        if (meta.isNotEmpty()) Text(meta, style = metaStyle, color = Color(palette.textSecondary), modifier = Modifier.padding(top = ORIGINAL_GAP))
        if (description.text.isNotEmpty()) {
            Text(text, style = textStyle, color = Color(palette.textPrimary), modifier = Modifier.padding(top = TEXT_TOP))
        }
    }
}

/**
 * A chapter (spec 5.4): its 96 x 54 frame, the title in 15 sp 650 up to two lines over its start time in 12.5 sp `textSecondary`;
 * padding 8 16; a tap seeks to the chapter ([onSeek]). One button for assistive technology. The sheet and the wide page's Chapters
 * segment both list these.
 */
@Composable
internal fun ChapterRow(chapter: Chapter, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val titleStyle = rememberTitleStyle(CHAPTER_TITLE_SP, chapter.title)
    val time = VideoText.duration(chapter.startMs / 1000)
    val timeStyle = rememberMetaStyle(CHAPTER_TIME_SP, time)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = VideoMetrics.ROW_PAD_V_DP.dp)
            .videoClickable(CosmoRadii.md, { onSeek(chapter.startMs) }, null),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailFrame(url = chapter.thumbnailUrl, modifier = Modifier.size(CHAPTER_FRAME_W, CHAPTER_FRAME_H), shape = CHAPTER_FRAME_SHAPE)
        Spacer(Modifier.width(CosmoSpacing.gutter))
        Column(Modifier.weight(1f)) {
            Text(chapter.title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(time, style = timeStyle.tabular(), color = Color(palette.textSecondary), maxLines = 1)
        }
    }
}
