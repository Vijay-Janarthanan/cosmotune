package com.cosmotune.video.ui.channel

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.model.ChannelPage
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_channel_about
import com.cosmotune.video.resources.video_channel_joined
import com.cosmotune.video.resources.video_channel_links
import com.music.bitchord.sharedui.resources.cosmo_read_more
import com.music.bitchord.sharedui.resources.cosmo_show_less
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.Res as SharedRes

/*
 * The About tab of a channel (spec 5.5): the description in a tile, the join date, the links.
 */

/** The About tile: a card (superellipse 24) with padding 16, at most this wide on a wide page. */
private val TILE_MAX_WIDTH = 640.dp
private val TILE_PADDING = 16.dp

/** The description shows this many lines, then "Read more" (as part two's About tile does). */
private const val DESCRIPTION_LINES = 4
private const val DESCRIPTION_SP = 14f
private const val DESCRIPTION_LINE_HEIGHT = 1.55f

/** Text rules of the About tab, apart from any window. */
internal object AboutText {
    /**
     * The join date as a line: the source's own text when it already says "Joined ..." ([word] is the first word of the sentence in the
     * app's language), else [formatted] (the sentence made from the date).
     */
    fun joined(raw: String, word: String, formatted: String): String = if (word.isNotEmpty() && raw.startsWith(word, ignoreCase = true)) raw else formatted

    /** Whether [url] is one the About tab opens (a web address). */
    fun openable(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** [url] as the link row prints it: without the scheme and a trailing slash. */
    fun display(url: String): String = url.removePrefix("https://").removePrefix("http://").removeSuffix("/")
}

/**
 * The About tab as one item of the page: the tile with "About", the description (four lines, then a text button to read the rest, shown
 * once the text has been laid out and has lost something), the join date, and under "Links" one 48 dp row for each address (a tap opens it).
 * With nothing to say it is the empty state.
 */
@Composable
internal fun ChannelAbout(page: ChannelPage, line: PageLine, wide: Boolean) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    val uriHandler = LocalUriHandler.current
    val description = page.channel.description?.takeIf { it.isNotBlank() }
    val joinedRaw = page.joinedText?.takeIf { it.isNotBlank() }
    val joinedWord = stringResource(Res.string.video_channel_joined, "").trim()
    val joined = joinedRaw?.let { AboutText.joined(it, joinedWord, stringResource(Res.string.video_channel_joined, it)) }
    val links = page.links.filter { it.isNotBlank() }
    if (description == null && joined == null && links.isEmpty()) {
        DefaultEmpty()
        return
    }
    val shape = remember { SuperellipseShape(CosmoRadii.card) }
    var expanded by remember(description) { mutableStateOf(false) }
    var clipped by remember(description) { mutableStateOf(false) }
    val title = stringResource(Res.string.video_channel_about)
    val bodyStyle = { text: String ->
        type.forText(
            TextStyle(
                fontFamily = type.textFamily,
                fontWeight = FontWeight.Normal,
                fontSize = DESCRIPTION_SP.sp,
                lineHeight = DESCRIPTION_SP.sp * DESCRIPTION_LINE_HEIGHT,
            ),
            text,
        )
    }
    Column(
        Modifier
            .padding(horizontal = line.own)
            .fillMaxWidth()
            .then(if (wide) Modifier.widthIn(max = TILE_MAX_WIDTH) else Modifier)
            .cosmoTile(shape)
            .padding(TILE_PADDING),
    ) {
        Text(
            text = title,
            style = type.forText(type.title(TypeScale.TITLE_S), title),
            color = Color(palette.textPrimary),
            modifier = Modifier.semantics { heading() },
        )
        if (description != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = bodyStyle(description),
                color = Color(palette.textSecondary),
                maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> if (!expanded) clipped = result.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth().then(if (reduced) Modifier else Modifier.animateContentSize()),
            )
            if (clipped || expanded) {
                // a text button has 12 dp of padding inside its label: the label stands on the text's edge
                CosmoButton(
                    text = stringResource(if (expanded) SharedRes.string.cosmo_show_less else SharedRes.string.cosmo_read_more),
                    onClick = { expanded = !expanded },
                    modifier = Modifier.offset(x = (-12).dp),
                    kind = CosmoButtonKind.TEXT,
                    size = CosmoButtonSize.SMALL,
                )
            }
        }
        if (joined != null) {
            Spacer(Modifier.height(if (description != null) 4.dp else 8.dp))
            Text(
                text = joined,
                style = bodyStyle(joined),
                color = Color(palette.textTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (links.isNotEmpty()) {
            val linksTitle = stringResource(Res.string.video_channel_links)
            Spacer(Modifier.height(16.dp))
            Text(
                text = linksTitle,
                style = type.forText(type.title(TypeScale.TITLE_S), linksTitle),
                color = Color(palette.textPrimary),
                modifier = Modifier.semantics { heading() },
            )
            for (link in links) {
                val shown = AboutText.display(link)
                val interaction = remember(link) { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .cosmoFocusRing(interaction, CosmoRadii.xs)
                        .clickable(interactionSource = interaction, indication = null, enabled = AboutText.openable(link), role = Role.Button) {
                            runCatching { uriHandler.openUri(link) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = shown,
                        style = bodyStyle(shown),
                        color = Color(palette.accentStrong),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
