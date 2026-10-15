package com.cosmotune.ui.states

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.blocks.rowSubtitleStyle
import com.cosmotune.ui.blocks.rowTitleStyle
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoFocusRing
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
 * and 8 dp above and below, with "Sign in to YouTube Music" over its one-line reason and a small primary button at the end.
 *
 * As the old banner did, a tap anywhere on the tile signs in: the tile is a button (its two lines are its label, "Sign in" its action,
 * with the ripple and the keyboard focus ring of the page's B controls), and the small button at the end stays, the one a finger aims
 * for.
 */
@Composable
fun CosmoSignInBanner(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = stringResource(Res.string.sign_in_youtube_music)
    val reason = stringResource(Res.string.personalized_recommendations)
    val action = stringResource(Res.string.sign_in)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = BANNER_VERTICAL)
            .cosmoFocusRing(interaction, BANNER_RADIUS)
            .cosmoTile(SuperellipseShape(BANNER_RADIUS))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClickLabel = action, role = Role.Button, onClick = onSignIn)
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
