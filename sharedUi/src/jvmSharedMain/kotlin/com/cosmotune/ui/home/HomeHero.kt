package com.cosmotune.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.components.liftShadow
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.data.model.HEADER_ART_PX
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.music.ui.screens.localizeCardSubtitle

/*
 * The hero of Home (`screens-v2.css` `.hero2`, `.hero-txt`, `.tag2`, `.play2`; `screens-v3.css` `.dh-bento .hero2` for the wide
 * page): the artwork of the first item of the feed filling a superellipse, a scrim from the middle down, a glass tag with the
 * shelf's name at the top, the title and the line under it at the bottom and the play button at the corner.
 */

/** The numbers that differ between the phone's hero and the desktop's (`.hero2` and `.dh-bento .hero2`). */
internal class HeroMetrics(
    val radius: Dp,
    val tagInset: Dp,
    val textStart: Dp,
    val textBottom: Dp,
    val textEnd: Dp,
    val playSize: Dp,
    val playInset: Dp,
    /** The title is `title(TITLE_L)` on the phone and `display(DISPLAY_S)` on the desktop (`.t-title-l`, `.t-display-s`). */
    val displayTitle: Boolean,
) {
    companion object {
        val PHONE = HeroMetrics(radius = 28.dp, tagInset = 12.dp, textStart = 16.dp, textBottom = 14.dp, textEnd = 84.dp, playSize = 56.dp, playInset = 14.dp, displayTitle = false)
        val WIDE = HeroMetrics(radius = 32.dp, tagInset = 18.dp, textStart = 26.dp, textBottom = 26.dp, textEnd = 120.dp, playSize = 64.dp, playInset = 24.dp, displayTitle = true)
    }
}

/** The hero's scrim starts at 30 % of its height and reaches 82 % of the palette's `scrim` at the bottom (`.hero-scrim`). */
private const val SCRIM_START = 0.3f
private const val SCRIM_ALPHA = 0.82f

/** The shadow of a hero: `0 2 4` black at 30 % under `0 22 44 -16` in the cover's shadow colour, as a 16 dp lift. */
private val HERO_ELEVATION = 16.dp
private const val HERO_AMBIENT_ALPHA = 0.30f

private val TAG_HEIGHT = 30.dp
private val TAG_SHAPE = SuperellipseShape(15.dp)

/**
 * The hero: [item]'s artwork cropped to fill the card, under a scrim. [tag] is the name of the shelf it came from, in a glass tag
 * at the top left. The title and [item]'s line are white over the scrim (the allowed exception: they sit on a picture, like Now
 * Playing over video). The play button is the floating (C) one; it and a tap on the card do the same, [onClick]; a hold, or a
 * right click, calls [onLongPress]. [playing] is whether [item] is the song that is playing now.
 */
@Composable
internal fun HomeHero(
    item: ShelfItem,
    tag: String,
    playing: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    metrics: HeroMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val shape = remember(metrics.radius) { SuperellipseShape(metrics.radius) }
    Box(
        modifier
            .semantics(mergeDescendants = true) { role = Role.Button }
            // The shadow takes the shape of the card and the cover's own colour; both are read in the layer block, so a fade moves it.
            .liftShadow(
                shape,
                HERO_ELEVATION,
                spot = { colors.live(CoverField.SHADOW).copy(alpha = 1f) },
                ambient = { Color.Black.copy(alpha = HERO_AMBIENT_ALPHA) },
            )
            .clip(shape)
            // The ground behind a picture that has not arrived yet (`.hero2`: `--c-surface`), read while drawing.
            .drawBehind { drawRect(colors.live(CoverField.SURFACE)) }
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().drawWithCache {
                // Transparent in the scrim's own hue, so the fade does not pass through grey.
                val scrim = colors.live(CoverField.SCRIM)
                val clear = scrim.copy(alpha = 0f)
                val brush = Brush.verticalGradient(
                    0f to clear,
                    SCRIM_START to clear,
                    1f to scrim.copy(alpha = scrim.alpha * SCRIM_ALPHA),
                )
                onDrawBehind { drawRect(brush) }
            },
        )
        // The tag floats over a picture: glass (C). It has no picture of its own to blur, so it is the solid tint.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(metrics.tagInset)
                .height(TAG_HEIGHT)
                .cosmoGlass(hazeState = null, shape = TAG_SHAPE)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tag,
                style = type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(650), fontSize = 12.5.sp), tag),
                color = Color(palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = metrics.textStart, bottom = metrics.textBottom, end = metrics.textEnd),
        ) {
            val titleStyle = if (metrics.displayTitle) type.display(TypeScale.DISPLAY_S) else type.title(TypeScale.TITLE_L)
            Text(
                text = item.title,
                style = type.forText(titleStyle, item.title),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                val line = localizeCardSubtitle(item.subtitle)
                Text(
                    text = line,
                    style = type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 13.5.sp * 1.35f), line),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        PlayMorphButton(
            playing = playing,
            loading = false,
            onClick = onClick,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = metrics.playInset, bottom = metrics.playInset),
            size = metrics.playSize,
            floating = true,
        )
    }
}
