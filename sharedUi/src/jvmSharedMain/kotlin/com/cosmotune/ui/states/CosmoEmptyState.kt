package com.cosmotune.ui.states

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/*
 * The empty state in Orbit: a glyph on a quiet disc, a headline in the wide face, one sentence in the dim ink and one peach action.
 * The same state stands for "nothing here", "no results" and "could not load" with a retry. Loading is Orbit's progress: the ring
 * with its moon going round.
 */

/** The disc the glyph stands on, and the glyph. */
private val ART_DISC = 64.dp
private val ICON_SIZE = 28.dp

/** The state's own rhythm: the gap of the column, the room around it, and how wide the message may run. */
private val GAP = 10.dp
private val PADDING_V = 48.dp
private val PADDING_H = 22.dp
private val MESSAGE_MAX_WIDTH = 300.dp

/** The loading page's gap between the orbit and its label, and the orbit's size. */
private val LOADING_GAP = 14.dp
private val LOADING_ORBIT = 44.dp

/**
 * A centred column on the page: [icon] on a quiet disc (the ink at 8 %), the [title] in the wide face when there is one, the
 * [message] in the dim ink and, when both [actionLabel] and [onAction] are given, the one peach action ([CosmoButton] PRIMARY).
 *
 * The old phone `MessageState` calls it with no title, no icon and the old parameters; the desktop's error and empty pages call it
 * with a title and, for the empty page, the icon of what is missing.
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
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PADDING_H, vertical = PADDING_V),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        EmptyArt(icon, look)
        if (title != null) {
            Text(
                text = title,
                color = look.ink,
                style = fonts.section,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            text = message,
            color = look.dim,
            style = fonts.body,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MESSAGE_MAX_WIDTH),
        )
        if (actionLabel != null && onAction != null) {
            CosmoButton(text = actionLabel, onClick = onAction, kind = CosmoButtonKind.PRIMARY, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * The page of a screen that is loading, on the desktop: Orbit's progress (the ring, 44 dp, its moon going round in the ink) over
 * [label], 14 dp apart, centred in the room the page has.
 */
@Composable
fun CosmoLoadingState(label: String, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LOADING_GAP)) {
            OrbitRing(LOADING_ORBIT, look.ink, progress = null, spinning = true)
            Text(
                text = label,
                color = look.dim,
                style = fonts.body,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The state's glyph on a quiet disc. Decoration: the title and the message say it. */
@Composable
private fun EmptyArt(icon: ImageVector, look: OrbitLook) {
    Box(Modifier.padding(bottom = 4.dp).size(ART_DISC).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = look.ink, modifier = Modifier.size(ICON_SIZE))
    }
}
