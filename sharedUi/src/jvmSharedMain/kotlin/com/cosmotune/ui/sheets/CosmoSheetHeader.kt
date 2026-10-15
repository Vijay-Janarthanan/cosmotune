package com.cosmotune.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.blocks.CircleCover
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

/*
 * The head of a song or album sheet (`.sh-head`, `screens-v2.css`): padding 0 20 16, a 56 dp cover of radius 14 and 14 dp of gap
 * before the text, the title 17 sp weight 700 and the subtitle 13.5 sp in `textSecondary`.
 */
private val HEAD_PADDING_H = 20.dp
private val HEAD_PADDING_BOTTOM = 16.dp
private val HEAD_GAP = 14.dp
private val HEAD_COVER = 56.dp
private val HEAD_COVER_RADIUS = 14.dp
private const val HEAD_TITLE_SP = 17f
private const val HEAD_SUBTITLE_SP = 13.5f

/** `.t-overline` in a sheet: padding 16 22 4. */
private val HEADING_PADDING_H = 22.dp
private val HEADING_PADDING_TOP = 16.dp
private val HEADING_PADDING_BOTTOM = 4.dp
private const val HEADING_SP = 12f

/**
 * What a sheet is about: the cover at [imageUrl] (a circle when [circle] is true, for an artist), the [title] and, when there is
 * one, the [subtitle]. The cover is a decoration; the two lines are the label of the header.
 */
@Composable
fun CosmoSheetHeader(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val titleStyle = remember(type, title) {
        type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Bold, fontSize = HEAD_TITLE_SP.sp, lineHeight = HEAD_TITLE_SP.sp * 1.25f),
            title,
        )
    }
    val subtitleStyle = remember(type, subtitle) {
        type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = HEAD_SUBTITLE_SP.sp, lineHeight = HEAD_SUBTITLE_SP.sp * 1.3f),
            subtitle.orEmpty(),
        )
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = HEAD_PADDING_H, end = HEAD_PADDING_H, bottom = HEAD_PADDING_BOTTOM)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (circle) CircleCover(imageUrl, HEAD_COVER, shadow = true) else CoverImage(imageUrl, HEAD_COVER, HEAD_COVER_RADIUS)
        Spacer(Modifier.width(HEAD_GAP))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = subtitleStyle, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The overline over a sheet's second half ("Add to playlist"): 12 sp weight 700 in `textSecondary`, padding 16 22 4. A heading for assistive technology. */
@Composable
fun CosmoSheetHeading(text: String) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val style = remember(type, text) {
        type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Bold, fontSize = HEADING_SP.sp, lineHeight = HEADING_SP.sp * 1.3f),
            text,
        )
    }
    Text(
        text = text,
        style = style,
        color = Color(palette.textSecondary),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HEADING_PADDING_H, end = HEADING_PADDING_H, top = HEADING_PADDING_TOP, bottom = HEADING_PADDING_BOTTOM)
            .semantics { heading() },
    )
}
