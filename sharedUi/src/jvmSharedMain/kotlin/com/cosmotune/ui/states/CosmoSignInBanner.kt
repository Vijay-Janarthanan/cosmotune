package com.cosmotune.ui.states

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.blocks.rowSubtitleStyle
import com.cosmotune.ui.blocks.rowTitleStyle
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.personalized_recommendations
import com.music.bitchord.sharedui.resources.sign_in
import com.music.bitchord.sharedui.resources.sign_in_youtube_music
import org.jetbrains.compose.resources.stringResource

/** The tile's corner (a B tile: `--r-card`, the superellipse) and what is inside it. */
private val BANNER_RADIUS = 24.dp
private val BANNER_PADDING = 16.dp
private val BANNER_VERTICAL = 8.dp
private val BANNER_GAP = 12.dp

/**
 * The prompt at the top of Home while signed out, as a B tile: the page's tile fill under a superellipse of 24, 16 dp from the sides
 * and 8 dp above and below, with "Sign in to YouTube Music" over its one-line reason and a small primary button at the end. The
 * button is the one control (the label is also its description); the tile is only the ground it stands on, so a screen reader and the
 * Tab key meet one "Sign in", not two.
 */
@Composable
fun CosmoSignInBanner(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = stringResource(Res.string.sign_in_youtube_music)
    val reason = stringResource(Res.string.personalized_recommendations)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = BANNER_VERTICAL)
            .cosmoTile(SuperellipseShape(BANNER_RADIUS))
            .padding(BANNER_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color(palette.textPrimary),
                style = type.forText(type.rowTitleStyle(), title),
            )
            Text(
                text = reason,
                color = Color(palette.textSecondary),
                style = type.forText(type.rowSubtitleStyle(), reason),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(BANNER_GAP))
        CosmoButton(text = stringResource(Res.string.sign_in), onClick = onSignIn, kind = CosmoButtonKind.PRIMARY, size = CosmoButtonSize.SMALL)
    }
}
