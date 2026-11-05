package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.podio.PodcastShow
import com.cosmotune.podio.RadioStation
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitArtwork
import com.cosmotune.ui.kit.KitLargeTopBar
import com.cosmotune.ui.kit.KitModeSwitcher
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular

/** A round icon button of Podio's bars (search, add, refresh): the kit's 40 dp tonal button, a plain button for screen readers. */
@Composable internal fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    CosmoIconButton(icon, label, onClick, style = CosmoIconButtonStyle.TONAL, size = 40.dp, enabled = enabled)
}

/**
 * A root page's top bar (design system 4.1): the kit's large light title that collapses on scroll, the mode switcher's pill in the
 * leading slot ([modeSwitcher]: the phone's root pages; the rail and the desktop sidebar carry their own), then the page's own
 * action, Search and the account at the end. [onHeight] hears the bar's height, for the list under it to start below the title.
 */
@Composable internal fun PodioTopBar(title: String, collapse: () -> Float, onSearch: (() -> Unit)?, onMusic: (() -> Unit)?, onHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, modeSwitcher: Boolean = LocalPodioModeSwitcher.current) {
    val density = LocalDensity.current
    val account = LocalModeProfileAction.current
    KitLargeTopBar(title, collapse, modifier.onSizeChanged { onHeight(with(density) { it.height.toDp() }) },
        leading = if (modeSwitcher) ({ KitModeSwitcher() }) else null,
        actions = {
            action?.invoke()
            if (onSearch != null) BarButton(CosmoIcons.Search, "Search", onSearch)
            when {
                account != null -> account()
                onMusic != null -> KitTextButton("Music", onMusic)
            }
        })
}

/** Whether Podio's root pages lead their bar with the mode pill: on the phone yes; the rail and the desktop sidebar have their own. */
internal val LocalPodioModeSwitcher = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A pushed page's small bar (a show, the search page): back, a title that fades in once the page's own header has scrolled away
 * ([titleShown], 0..1, read in the layer phase), and the page's actions. Ground and hairline appear with the title.
 */
@Composable internal fun PodioSmallBar(title: String, titleShown: () -> Float, onBack: () -> Unit, modifier: Modifier = Modifier, backLabel: String = "Back",
    actions: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Box(modifier.fillMaxWidth().drawBehind {
        val shown = titleShown().coerceIn(0f, 1f)
        drawRect(Color(palette.backgroundBottom).copy(alpha = shown))
        if (shown > 0f) drawRect(Color(palette.textPrimary).copy(alpha = .08f * shown), Offset(0f, size.height - 1f), Size(size.width, 1f))
    }.windowInsetsPadding(WindowInsets.statusBars).height(56.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CosmoIconButton(CosmoIcons.ArrowLeft, backLabel, onBack, style = CosmoIconButtonStyle.TONAL, size = 40.dp)
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Text(title, style = type.titleLarge, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp).graphicsLayer { alpha = titleShown().coerceIn(0f, 1f) })
    }
}

/** A notice or an error along the foot of the page, over the content (design system 4.21): one line, one action. */
@Composable internal fun PodioBanner(text: String, actionLabel: String, onAction: () -> Unit, danger: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 8.dp).clip(RoundedCornerShape(CosmoRadii.rMd)).background(Color(palette.surfaceHigh))
        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (danger) Box(Modifier.size(8.dp).clip(RoundedCornerShape(CosmoRadii.rXs)).background(Color(palette.danger)))
        if (danger) Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = type.bodyText, color = Color(if (danger) palette.danger else palette.textPrimary), maxLines = 3, overflow = TextOverflow.Ellipsis)
        KitTextButton(actionLabel, onAction)
    }
}

/** A light page heading in the list itself (the desktop shows its title as the first item of the page, design system 4.1). */
@Composable internal fun PageHeading(text: String, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(text, modifier.padding(horizontal = CosmoSpacing.margin, vertical = 8.dp).semantics { heading() }, style = type.forText(type.titleLarge, text),
        color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** One line of secondary text on the page (a count, a hint, a credit). */
@Composable internal fun MetaLine(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(text, modifier.padding(horizontal = CosmoSpacing.margin), style = (if (small) type.caption else type.meta),
        color = Color(if (small) palette.textTertiary else palette.textSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** A chart's row (design system 6.3, "Top in Portugal"): the rank, the 56 dp cover, the show over its publisher. */
@Composable internal fun ShowRow(show: PodcastShow, rank: Int?, onClick: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).combinedClickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = CosmoSpacing.margin, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rank != null) Text(rank.toString(), Modifier.width(32.dp), style = type.meta.tabular(), color = Color(palette.textTertiary), maxLines = 1)
        KitArtwork(show.artwork.takeIf(String::isNotBlank), KitArt.SQUARE, 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(show.title.ifBlank { "Podcast" }, style = type.forText(type.rowTitle, show.title), color = Color(palette.textPrimary),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (show.author.isNotBlank()) Text(show.author, style = type.meta, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A station on a shelf (Favourites): its logo tile, its name, its place; a tap plays it. */
@Composable internal fun StationCard(station: RadioStation, onClick: () -> Unit, width: Dp = 120.dp) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(Modifier.width(width).combinedClickable(role = Role.Button, onClickLabel = "Play", onClick = onClick)) {
        StationLogo(station, width, radius = CosmoRadii.rMd)
        Spacer(Modifier.height(8.dp))
        Text(station.name, style = type.forText(type.rowTitle, station.name), color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val place = station.placeLine()
        if (place.isNotBlank()) Text(place, style = type.meta, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
