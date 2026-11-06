package com.cosmotune.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.orbit.WatchSkeleton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A page with nothing to show (an empty library, no results, no downloads), in Orbit: the one empty state ([CosmoEmptyState]: the
 * [icon] on a quiet disc, the [title] in the wide face, the [message] in the dim ink, and the action when [actionLabel] and
 * [onAction] are given). Centred, no container.
 */
@Composable
fun KitEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = CosmoIcons.Library,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CosmoEmptyState(message = message, modifier = modifier, title = title, icon = icon, actionLabel = actionLabel, onAction = onAction)
}

/** Something failed (the network, a source): [KitEmptyState] with a retry. */
@Composable
fun KitErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, title: String = "Something went wrong", retryLabel: String = "Try again") {
    KitEmptyState(title = title, message = message, modifier = modifier, icon = CosmoIcons.Replay, actionLabel = retryLabel, onAction = onRetry)
}

/** A thin offline notice at the top of a page, in Orbit: a quiet card (the ink at 5 %, radius 22), the danger dot and one line in the body face, never blocking the page under it. */
@Composable
fun KitOfflineBanner(modifier: Modifier = Modifier, text: String = "You're offline. Showing what's saved on this device.") {
    val palette = LocalCosmoColors.current.palette
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(look.row).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(palette.danger)))
        Spacer(Modifier.width(10.dp))
        Text(text, style = fonts.body, color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * [count] stand-in rows while a list loads, in the shape the Orbit row ([com.cosmotune.ui.orbit.OrbitSongRow]) will take: a 68 dp
 * block at radius 22 on the ink at 5 %, a disc (a rounded 16:9 block for [KitArt.WIDE], a poster for [KitArt.POSTER]) and two
 * lines, 3 dp apart, the page's margin at the sides. Still: nothing moves under reduced motion or otherwise.
 */
@Composable
fun KitSkeletonRows(count: Int = 6, modifier: Modifier = Modifier, art: KitArt = KitArt.SQUARE) {
    val look = orbitLook()
    Column(modifier.fillMaxWidth()) {
        repeat(count) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 3.dp).height(68.dp)
                    .clip(RoundedCornerShape(22.dp)).background(look.row).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (art) {
                    KitArt.WIDE -> WatchSkeleton(look, Modifier.size(64.dp, 36.dp), radius = 10.dp)
                    KitArt.POSTER -> WatchSkeleton(look, Modifier.size(32.dp, 48.dp), radius = 8.dp)
                    else -> WatchSkeleton(look, Modifier.size(44.dp), radius = 22.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchSkeleton(look, Modifier.fillMaxWidth(0.7f).height(12.dp), radius = 6.dp)
                    WatchSkeleton(look, Modifier.fillMaxWidth(0.45f).height(10.dp), radius = 5.dp)
                }
            }
        }
    }
}

/**
 * The navigation rail of a wide window (the dock's tabs, standing on the start edge), in Orbit: each [CosmoTab] its icon over its
 * label, the chosen one's icon on a solid ink pill in the ground's colour with its label in the ink, the others in the dim ink with a
 * faint wash under the pointer. Every one is labelled; the rail is a group of tabs for screen readers.
 */
@Composable
fun KitNavRail(tabs: List<CosmoTab>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, header: @Composable ColumnScope.() -> Unit = {}) {
    val look = orbitLook()
    val fonts = orbitFonts()
    Column(modifier.widthIn(min = 88.dp).padding(vertical = 12.dp).selectableGroup(), horizontalAlignment = Alignment.CenterHorizontally) {
        header()
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            Column(
                Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .wrapContentWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(56.dp, 32.dp).clip(CircleShape).background(if (selected) look.ink else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = if (selected) look.ground else look.dim, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(tab.label, style = fonts.body.copy(fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight(700)), color = if (selected) look.ink else look.dim, maxLines = 1)
            }
        }
    }
}

/**
 * A bottom sheet of the kit, in Orbit: [SurfaceSheetSwitch], so the sheet is [com.cosmotune.ui.sheets.CosmoSheetSurface] (a flat
 * sheet of its own ground with 32 dp top corners and the grabber, the 40 % scrim), with an optional [title] in the wide face. The
 * content is held to 85 % of the window's height, so a list inside it that fills its sheet (a lazy one) still has a height to fill.
 * Its rows are [KitSettingsRow]s or [KitMediaRow]s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    SurfaceSheetSwitch(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = windowHeight * 0.85f)) {
            if (!title.isNullOrBlank()) {
                Text(
                    title, style = fonts.section, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp).semantics { heading() },
                )
            }
            content()
        }
    }
}
