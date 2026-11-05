package com.cosmotune.ui.states

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.heroFill
import com.cosmotune.ui.components.tileEdge
import com.cosmotune.ui.components.tonalFill
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

/*
 * The empty state (DESIGN_SYSTEM.md 4.15; a glyph, a headline, one sentence and one action; earlier: "three 64 dp tiles, radius 18; secondary fill at 60 % and 80 %,
 * the front tile in the play gradient"). The same state stands for "nothing here", "no results" and "could not load" with a retry.
 */

private val ICON_SIZE = 48.dp

/** The state's own rhythm: the gap of the column (`gap: 10px`), the room around it, and how wide the message may run. */
private val GAP = 10.dp
private val PADDING_V = 48.dp
private val PADDING_H = 22.dp
private val MESSAGE_MAX_WIDTH = 300.dp

/** The loading page's gap between the blob and its label, and the blob's size. */
private val LOADING_GAP = 14.dp
private val LOADING_BLOB = 40.dp




/**
 * A centred column on the page: the art (three superellipse tiles, two turned and tinted behind a front one in the play gradient that
 * carries [icon]), the [title] in Archivo when there is one, the [message] in [textSecondary][com.cosmotune.theme.CoverColors.textSecondary]
 * and, when both [actionLabel] and [onAction] are given, a primary button. It is a block of the page (B), so the buttons are B.
 *
 * The old phone `MessageState` calls it with no title, no icon and the old parameters; the desktop's error and empty pages call it
 * with a title and, for the empty page, the icon of what is missing (an icon that is not one of ours still reads in the front tile: it
 * is drawn in `onAccent` whatever it is).
 */
@Composable
fun CosmoEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = CosmoIcons.Play,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PADDING_H, vertical = PADDING_V),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        EmptyArt(icon, colors)
        if (title != null) {
            Text(
                text = title,
                color = Color(palette.textPrimary),
                style = type.forText(type.emptyTitleStyle(), title),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            text = message,
            color = Color(palette.textSecondary),
            style = type.forText(type.emptyMessageStyle(), message),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MESSAGE_MAX_WIDTH),
        )
        if (actionLabel != null && onAction != null) {
            CosmoButton(text = actionLabel, onClick = onAction, kind = CosmoButtonKind.PRIMARY)
        }
    }
}

/**
 * The page of a screen that is loading, on the desktop: the loading blob (40 dp, the cover's accent) over [label], 14 dp apart,
 * centred in the room the page has. Under reduced motion the blob stands still as a circle.
 */
@Composable
fun CosmoLoadingState(label: String, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LOADING_GAP)) {
            CosmoLoadingBlob(Modifier.size(LOADING_BLOB))
            Text(
                text = label,
                color = Color(palette.textSecondary),
                style = type.forText(type.emptyMessageStyle(), label),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The state's glyph (DESIGN_SYSTEM.md 4.15): 48 dp in the tertiary ink, no illustration. Decoration: the title and the message say it. */
@Composable
private fun EmptyArt(icon: ImageVector, colors: CosmoColors) {
    Icon(icon, contentDescription = null, tint = Color(colors.palette.textTertiary), modifier = Modifier.size(ICON_SIZE))
}

/** The headline: `titleMedium`. */
private fun CosmoType.emptyTitleStyle(): TextStyle = titleMedium

/** The sentence under it: `body`. */
private fun CosmoType.emptyMessageStyle(): TextStyle = bodyText
