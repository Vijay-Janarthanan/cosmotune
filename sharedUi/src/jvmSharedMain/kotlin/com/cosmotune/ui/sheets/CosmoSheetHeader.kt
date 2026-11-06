package com.cosmotune.ui.sheets

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.blocks.CircleCover
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

/*
 * The head of a song or album sheet in Orbit (board batch-a, section 4): padding 0 14 16, the cover as a 64 dp disc (rule 1) and 14 dp
 * of gap before the text, the title 16 sp weight 700 and the subtitle 13 sp in the dim ink.
 */
private val HEAD_PADDING_H = 14.dp
private val HEAD_PADDING_BOTTOM = 16.dp
private val HEAD_GAP = 14.dp
private val HEAD_COVER = 64.dp
private const val HEAD_TITLE_SP = 16f
private const val HEAD_SUBTITLE_SP = 13f

/** The explicit mark in front of the title: the catalogue's outlined "E" (`ExplicitSongTitle`'s), in the title's ink. */
private val MARK_BORDER = 1.dp
private const val MARK_BORDER_ALPHA = 0.72f
private val MARK_SHAPE = RoundedCornerShape(CosmoRadii.rXs)
private val MARK_PADDING = 3.dp
private val MARK_GAP = 6.dp
private const val MARK_SP = 11f

/** The mono overline in a sheet: padding 16 18 4. */
private val HEADING_PADDING_H = 18.dp
private val HEADING_PADDING_TOP = 16.dp
private val HEADING_PADDING_BOTTOM = 4.dp

/**
 * What a sheet is about: the cover at [imageUrl] as a disc (every cover is one in Orbit; [circle], for an artist, is kept for the
 * callers), the [title] and, when there is one, the [subtitle]. The cover is a decoration; the two lines are the label of the header. An [explicit] song shows the outlined
 * "E" in front of its title, as the old header did.
 */
@Composable
fun CosmoSheetHeader(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
    explicit: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val fonts = orbitFonts()
    val titleStyle = remember(type, fonts, title) {
        type.forText(fonts.title.copy(fontSize = HEAD_TITLE_SP.sp, lineHeight = HEAD_TITLE_SP.sp * 1.25f), title)
    }
    val subtitleStyle = remember(type, fonts, subtitle) {
        type.forText(fonts.body.copy(fontSize = HEAD_SUBTITLE_SP.sp, lineHeight = HEAD_SUBTITLE_SP.sp * 1.3f), subtitle.orEmpty())
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = HEAD_PADDING_H, end = HEAD_PADDING_H, bottom = HEAD_PADDING_BOTTOM)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (circle) CircleCover(imageUrl, HEAD_COVER, shadow = false) else Disc(imageUrl, HEAD_COVER)
        Spacer(Modifier.width(HEAD_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (explicit) ExplicitMark(Color(palette.textPrimary))
                Text(text = title, style = titleStyle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = subtitleStyle, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The outlined "E" of an explicit song, 6 dp before the title: [ink] at 72 % for the 1 dp border, as `ExplicitSongTitle` draws it. */
@Composable
private fun ExplicitMark(ink: Color) {
    val type = LocalCosmoType.current
    val style = remember(type) { type.label.copy(fontSize = MARK_SP.sp, lineHeight = MARK_SP.sp * 1.3f) }
    Text(
        text = "E",
        style = style,
        color = ink,
        modifier = Modifier.border(MARK_BORDER, ink.copy(alpha = MARK_BORDER_ALPHA), MARK_SHAPE).padding(horizontal = MARK_PADDING),
    )
    Spacer(Modifier.width(MARK_GAP))
}

/** The overline over a sheet's second half ("Add to playlist"): the mono label in `textSecondary`, padding 16 18 4. A heading for assistive technology. */
@Composable
fun CosmoSheetHeading(text: String) {
    val palette = LocalCosmoColors.current.palette
    val style = orbitFonts().label
    Text(
        text = text.uppercase(),
        style = style,
        color = Color(palette.textSecondary),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HEADING_PADDING_H, end = HEADING_PADDING_H, top = HEADING_PADDING_TOP, bottom = HEADING_PADDING_BOTTOM)
            .semantics { heading() },
    )
}
