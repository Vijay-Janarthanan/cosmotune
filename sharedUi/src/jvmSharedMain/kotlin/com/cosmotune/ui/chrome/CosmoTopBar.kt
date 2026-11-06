package com.cosmotune.ui.chrome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.CosmoBrand
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.cosmoTopGround
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_back
import com.cosmotune.music.sharedui.resources.ic_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The top bar of the phone's pages (`.topbar2` of the boards), the same height as the bar it replaces: the caller passes
 * [height], the phone's `topBarHeight()` with the status bar in it, so every page's top padding stays right. Under the status
 * bar a 48 dp row with 4 dp above it, 16 dp at the sides and 8 dp between its parts:
 *
 * - [onBack] given: a 40 dp Orbit round back button; without it, the logo mark, 28 x 18 dp, in the brand
 *   colour of the theme ([brandMarkColor]);
 * - the [title], one line, in `textPrimary`, with [trailingTitle] after it in `textSecondary`. It is invisible at rest and
 *   fades in (the effects spring) once the page has [scrolled] past its own big heading, or when there is a trailing title.
 *   With an [accessory] the accessory is the bar's content and the title is not drawn: the page's field says what the page is;
 * - [accessory] fills the rest of the row (the Search tab's field), then [actions], 6 dp apart.
 *
 * At rest the bar draws nothing of its own: the root's top fade is the backdrop, and the icon buttons are glass circles. Once
 * [scrolled], it stands on the page's ground ([cosmoTopGround]) so nothing scrolling under it shows through its title. Under it
 * hangs [CosmoPullIndicator], which takes no height. [pullFraction] is a lambda so that a drag recomposes nothing.
 */
@Composable
fun CosmoTopBar(
    title: String,
    scrolled: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    trailingTitle: String? = null,
    onBack: (() -> Unit)? = null,
    refreshing: Boolean = false,
    pullFraction: () -> Float = { 0f },
    accessory: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    val titleShown = scrolled || trailingTitle != null
    val titleAlpha = animateFloatAsState(
        targetValue = if (titleShown) 1f else 0f,
        animationSpec = CosmoMotion.effects(reduced),
        label = "topBarTitleAlpha",
    )
    // Once the page has scrolled under the bar, the bar stands on the page's own ground (the top of its gradient and cover wash),
    // so the page's words go under it rather than under the bar's.
    val groundAlpha = animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = CosmoMotion.effects(reduced),
        label = "topBarGroundAlpha",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .then(if (LocalCosmoTopBarGround.current) Modifier.cosmoTopGround(shown = { groundAlpha.value }) else Modifier)
                .statusBarsPadding()
                // The Orbit back button is its 40 dp circle with no wider target round it: it stands on the page margin as it is.
                .padding(start = if (onBack != null) TopBarMetrics.SIDE_WITH_BACK else TopBarMetrics.SIDE, top = TopBarMetrics.TOP, end = TopBarMetrics.SIDE),
            horizontalArrangement = Arrangement.spacedBy(TopBarMetrics.GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                // The Orbit round button (the kit's 40 dp tonal circle in the page's ink), not glass.
                com.cosmotune.ui.orbit.OrbitRoundButton(
                    icon = CosmoIcons.ArrowLeft,
                    label = stringResource(Res.string.cosmo_back),
                    ink = Color(palette.textPrimary),
                    onClick = onBack,
                    size = 40.dp,
                    onInk = Color(palette.backgroundTop),
                )
            } else if (LocalCosmoShell.current != null) {
                // A root page leads with the mode switcher (DESIGN_SYSTEM.md 4.2): the same pill in every mode, glyph only once scrolled.
                com.cosmotune.ui.kit.KitModeSwitcher(collapsed = scrolled)
            } else {
                Icon(
                    painter = painterResource(Res.drawable.ic_logo),
                    contentDescription = null,
                    tint = brandMarkColor(LocalCosmoColors.current.dark),
                    modifier = Modifier.size(TopBarMetrics.LOGO_WIDTH, TopBarMetrics.LOGO_HEIGHT),
                )
            }
            if (accessory != null) {
                // The page's own control takes the rest of the row, with the bar's gap before the actions.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { accessory() }
            } else {
                Row(
                    Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = titleAlpha.value }
                        // Invisible is silent: the page has its own heading.
                        .semantics { if (!titleShown) hideFromAccessibility() else heading() },
                    horizontalArrangement = Arrangement.spacedBy(TopBarMetrics.GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = type.forText(type.title(TypeScale.TITLE_S), title),
                        color = Color(palette.textPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (trailingTitle != null) {
                        Text(
                            text = trailingTitle,
                            style = type.forText(type.title(TypeScale.TITLE_S), trailingTitle),
                            color = Color(palette.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            // The page's actions are the old screens' own Material icon buttons: under the glass style the bridge's IconButton draws each
            // as a glass circle, like the back button at the other end.
            Row(horizontalArrangement = Arrangement.spacedBy(TopBarMetrics.ACTIONS_GAP), verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalCosmoActionStyle provides CosmoActionStyle.GLASS) { actions() }
            }
        }
        CosmoPullIndicator(fraction = pullFraction, refreshing = refreshing)
    }
}

/**
 * Whether [CosmoTopBar] lays the page's ground under itself once scrolled. A host that paints its own backdrop behind the bar (Video,
 * on its flat ground) provides false.
 */
val LocalCosmoTopBarGround = staticCompositionLocalOf { true }

/**
 * The colour of the logo mark where it stands alone on the page: apricot on a dark theme, ink on a light one (the brand rule
 * of `CosmoBrand`: apricot is 1.67:1 on white, so never there). Shared with the profile drawer's foot.
 */
internal fun brandMarkColor(dark: Boolean): Color = Color(if (dark) CosmoBrand.APRICOT else CosmoBrand.INK)

/** The numbers of `.topbar2`. */
internal object TopBarMetrics {
    val SIDE = 16.dp
    val SIDE_WITH_BACK = 16.dp
    val TOP = 4.dp
    val GAP = 8.dp
    val ACTIONS_GAP = 6.dp
    val LOGO_WIDTH = 28.dp
    val LOGO_HEIGHT = 18.dp
}
