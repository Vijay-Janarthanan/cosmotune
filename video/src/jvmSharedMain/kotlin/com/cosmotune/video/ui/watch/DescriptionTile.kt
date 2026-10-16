package com.cosmotune.video.ui.watch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.model.Description
import com.cosmotune.video.model.DescriptionLink
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_description
import org.jetbrains.compose.resources.stringResource

/*
 * The description tile (spec 5.4, item 4): `cosmoTile`, superellipse 24, margin 8 16, padding 14 16, three lines of 14 sp
 * `textSecondary` at line height 1.55; a tap opens the description sheet. The wide page shows the whole text in the same tile, with
 * its timestamps and links live (spec 5.4 Wide: "the description ... expanded inline").
 */

private val TILE_SHAPE = SuperellipseShape(24.dp)
private val TILE_MARGIN_V = 8.dp
private val TILE_PAD_H = 16.dp
private val TILE_PAD_V = 14.dp
private const val TEXT_SP = 14f
private const val TEXT_LINE = 1.55f
private const val TILE_LINES = 3

/** The description's text style: 14 sp at line height 1.55, taller lines for the scripts that need them. */
internal fun CosmoType.descriptionStyle(text: String): TextStyle =
    forText(TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = TEXT_SP.sp, lineHeight = TEXT_SP.sp * TEXT_LINE), text)

/**
 * The phone's tile: the first three lines of [description] (spec 5.4); one button, "Description", that calls [onOpen]. The caller
 * leaves it out when the text is empty (a download keeps no description).
 */
@Composable
internal fun DescriptionTile(description: Description, onOpen: () -> Unit, sidePadding: Dp, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val style = remember(type, description.text) { type.descriptionStyle(description.text) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding, vertical = TILE_MARGIN_V)
            .cosmoTile(TILE_SHAPE)
            .clickable(onClickLabel = stringResource(Res.string.video_description), role = Role.Button, onClick = onOpen)
            .padding(horizontal = TILE_PAD_H, vertical = TILE_PAD_V),
    ) {
        Text(description.text, style = style, color = Color(palette.textSecondary), maxLines = TILE_LINES, overflow = TextOverflow.Ellipsis)
    }
}

/** The wide page's tile: the whole of [description], its timestamps seeking ([onSeek]) and its links opening in the browser. */
@Composable
internal fun DescriptionInline(description: Description, onSeek: (Long) -> Unit, sidePadding: Dp, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val style = remember(type, description.text) { type.descriptionStyle(description.text) }
    val text = rememberDescriptionText(description, onSeek)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding, vertical = TILE_MARGIN_V)
            .cosmoTile(TILE_SHAPE)
            .padding(horizontal = TILE_PAD_H, vertical = TILE_PAD_V),
    ) {
        Text(text, style = style, color = Color(palette.textSecondary))
    }
}

/**
 * [description] as text with its links (spec 5.4: "tappable timestamps and links"): a timestamp seeks the video through [onSeek],
 * an address opens in the browser; both are drawn in the palette's `accentStrong`, weight 600. Built again only when the text or
 * the palette changes; the seek always reaches the latest [onSeek].
 */
@Composable
internal fun rememberDescriptionText(description: Description, onSeek: (Long) -> Unit): AnnotatedString {
    val palette = LocalCosmoColors.current.palette
    val seek = rememberUpdatedState(onSeek)
    return remember(description, palette) {
        val styles = TextLinkStyles(SpanStyle(color = Color(palette.accentStrong), fontWeight = FontWeight.SemiBold))
        buildAnnotatedString {
            append(description.text)
            for (link in descriptionLinks(description)) {
                val ms = link.timestampMs
                val url = link.url
                when {
                    ms != null -> addLink(LinkAnnotation.Clickable("t${link.start}", styles) { seek.value(ms) }, link.start, link.end)
                    url != null -> addLink(LinkAnnotation.Url(url, styles), link.start, link.end)
                }
            }
        }
    }
}

/**
 * The links of [description] that can be drawn (spec 5.4): inside the text, a timestamp, or an address on the web (http or https:
 * nothing else is handed to the system), in order and none overlapping the one before.
 */
internal fun descriptionLinks(description: Description): List<DescriptionLink> {
    val length = description.text.length
    var end = 0
    return description.links
        .filter { link ->
            val web = link.url?.let { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) } == true
            link.start >= 0 && link.start < link.end && link.end <= length && (link.timestampMs != null || web)
        }
        .sortedBy { it.start }
        .filter { link -> (link.start >= end).also { kept -> if (kept) end = link.end } }
}
