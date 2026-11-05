package com.cosmotune.video.ui.watch

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.model.Description
import com.cosmotune.video.model.DescriptionLink
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_description
import com.cosmotune.video.resources.video_guide_show_less
import com.cosmotune.video.resources.video_show_more
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * The description's text (DESIGN_SYSTEM.md 6.2): on the phone it is the sheet's; a wide page shows it under the title, on the page with no box
 * around it, collapsed to three lines with "more" and "Show less", its timestamps and links live.
 */

private const val COLLAPSED_LINES = 3

/** The description's text style: the One `bodyText`, taller lines for the scripts that need them. */
internal fun CosmoType.descriptionStyle(text: String): TextStyle = forText(bodyText, text)

/** The wide page's description: [description] collapsed to three lines, its timestamps seeking ([onSeek]) and its links opening in the browser. */
@Composable
internal fun DescriptionInline(description: Description, onSeek: (Long) -> Unit, sidePadding: Dp, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val style = remember(type, description.text) { type.descriptionStyle(description.text) }
    val text = rememberDescriptionText(description, onSeek)
    var expanded by rememberSaveable(description.text) { mutableStateOf(false) }
    val toggle = stringResource(if (expanded) Res.string.video_guide_show_less else Res.string.video_show_more)
    Column(modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = 8.dp).animateContentSize()) {
        Text(text, style = style, color = Color(palette.textSecondary), maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES, overflow = TextOverflow.Ellipsis)
        Text(
            toggle,
            style = type.control,
            color = Color(palette.textPrimary),
            modifier = Modifier.padding(top = 4.dp).clickable(role = Role.Button, onClickLabel = stringResource(Res.string.video_description)) { expanded = !expanded },
        )
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
