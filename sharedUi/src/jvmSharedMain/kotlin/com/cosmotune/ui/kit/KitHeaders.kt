package com.cosmotune.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.CoverPaletteLoader
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import androidx.compose.foundation.layout.heightIn
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/*
 * The kit: the mode-agnostic building blocks every mode (Music, Video, Manga, Read, Podio, Library, Settings) is rebuilt on, so
 * the whole app has one UI. Every piece takes its colours from the Cosmo palette (near-neutral grounds, one accent) and its type
 * from CosmoType (one family, light titles). See docs/design/REVAMP_DIRECTION.md for the direction and the API list.
 */

/**
 * A page's top bar with a large, light title (Prism's "Library", "Search", "Settings") that collapses as the page scrolls.
 * [collapse] is read in the layer and draw phases only (0 = the large title under the bar, 1 = scrolled away: the small title
 * centred in the bar and a hairline under it), so a scroll recomposes nothing. [onBack] adds a back button; [actions] sit at the
 * end (round ghost icon buttons, [KitIconButton]); [leading] sits at the start (the mode pill on a root page). The bar pads itself
 * below the status bar unless [statusBarPadding] is false (a page whose host already pads for it).
 */
@Composable
fun KitLargeTopBar(
    title: String,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
    overline: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    actions: @Composable RowScope.() -> Unit = {},
    statusBarPadding: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val c = collapse().coerceIn(0f, 1f)
                drawRect(colors.live(CoverField.BACKGROUND_BOTTOM).copy(alpha = c))
                if (c > 0f) drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = 0.08f * c), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1f), size = androidx.compose.ui.geometry.Size(size.width, 1f))
            }
            .then(if (statusBarPadding) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().height(BAR_HEIGHT)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) KitIconButton(CosmoIcons.ArrowLeft, backLabel, onBack)
                // The mode switcher's pill on a root page (KitModePill), or anything else that leads the bar.
                leading?.invoke()
                Box(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            // The small title fades in as the large one scrolls away.
            Text(
                title,
                style = type.titleLarge,
                color = Color(palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp).graphicsLayer { alpha = ((collapse() - 0.6f) / 0.4f).coerceIn(0f, 1f) },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, bottom = 8.dp)
                .graphicsLayer {
                    val c = collapse().coerceIn(0f, 1f)
                    alpha = 1f - c
                    translationY = -c * 24.dp.toPx()
                },
        ) {
            if (!overline.isNullOrBlank()) {
                Text(overline.uppercase(), style = type.overline, color = Color(palette.textSecondary), maxLines = 1)
            }
            Text(
                title,
                style = type.forText(type.displayLarge, title),
                color = Color(palette.textPrimary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/**
 * A page's large title as the first item of its scroll (DESIGN_SYSTEM.md 4.1), for a page whose bar stands apart from its list: `displayLarge`
 * on the page's margin, the page's own [actions] at its end. It scrolls away under the bar, which then shows the small title.
 */
@Composable
fun KitPageTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = CosmoSpacing.margin, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = type.forText(type.displayLarge, title),
            color = Color(palette.textPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * A section's header in Orbit: an optional mono [overline] ("MADE FOR YOU", [OrbitLabel]) over the [title] in the wide face
 * ([OrbitPageSection]), and [seeAllLabel] in the accent at the end when [onSeeAll] is given. Sits directly on the page, no container.
 */
@Composable
fun KitSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    seeAllLabel: String = "See all",
    onSeeAll: (() -> Unit)? = null,
) {
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = 4.dp, top = 32.dp, bottom = 12.dp)) {
        if (!overline.isNullOrBlank()) OrbitLabel(overline, orbitFonts(), look.dim, Modifier.padding(bottom = 6.dp))
        OrbitPageSection(title, action = seeAllLabel.takeIf { onSeeAll != null }, onAction = onSeeAll)
    }
}

/** A section header's text button: level with the one-line title, its 48 dp target overflowing the title's 28 dp without making the row taller. */
internal fun Modifier.sectionActionSlot(): Modifier = height(28.dp).wrapContentHeight(unbounded = true)

/** The fraction a page has scrolled its large title away: 0 at the top, 1 once [thresholdPx] has gone by. For [KitLargeTopBar]'s `collapse`. */
fun collapseFraction(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, thresholdPx: Float): Float =
    if (firstVisibleItemIndex > 0) 1f else (firstVisibleItemScrollOffset / thresholdPx).coerceIn(0f, 1f)

/** A round ghost icon button for bars and the player (like, more, search): 40 dp, a faint ink wash, the theme's ink. */
@Composable
fun KitIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false) {
    CosmoIconButton(icon = icon, contentDescription = label, onClick = onClick, modifier = modifier, style = CosmoIconButtonStyle.TONAL, size = 40.dp, on = on)
}

/**
 * The cover colour behind a detail header (DESIGN_SYSTEM.md 2.3): the page's own [url], not what is playing, seeds a gradient that
 * fades to the page's ground over the height it is given (320 dp). The list below stands on plain ground. Nothing is drawn while the
 * cover loads, with "Colours follow the cover" off, or without a cover.
 */
@Composable
fun KitCoverGradient(url: String?, modifier: Modifier = Modifier) {
    val shell = LocalCosmoShell.current
    val dark = LocalCosmoColors.current.dark
    val context = LocalPlatformContext.current
    val follow = shell?.settings?.coverColours?.collectAsState()?.value == true
    val amoled = shell?.settings?.amoled?.collectAsState()?.value == true
    val top by produceState<Int?>(null, url, dark, amoled, follow) {
        value = if (follow && url != null) CoverPaletteLoader.load(context, url, dark, amoled = amoled)?.accentContainer else null
    }
    val seed = top ?: return
    Box(modifier.drawBehind { drawRect(Brush.verticalGradient(listOf(Color(seed), Color(seed).copy(alpha = 0f)))) })
}

/**
 * What ends a root page's top bar on every root page of every mode (DESIGN_SYSTEM.md 4.1, 5.2): Search, then the account's avatar.
 * [account] is the avatar button (the host's own, which opens the account sheet); [pageAction] (at most one) comes before Search;
 * [showSearch] false is the Search page's own bar.
 */
@Composable
fun KitRootActions(onSearch: () -> Unit, searchLabel: String, account: @Composable () -> Unit = {}, pageAction: @Composable () -> Unit = {}, showSearch: Boolean = true) {
    pageAction()
    if (showSearch) KitIconButton(CosmoIcons.Search, searchLabel, onSearch)
    account()
}

private val BAR_HEIGHT = 56.dp
