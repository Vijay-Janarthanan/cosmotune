package com.cosmotune.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.orbit.OrbitBadge
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.orbitDockFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitTabName

/*
 * The navigation rule of the whole app (the shared board's A5, the Music board's 9): a window under 600 dp wide has the bottom bar
 * with the mini player above it ([CosmoBottomChrome]); from 600 dp the tabs stand in a side rail ([CosmoNavRail]) and the mini player
 * runs along the foot of the page beside it. A mode plugs its tabs (and its search) into [CosmoNavShell] and draws its own pages and
 * player; the shell decides which of the two it gets.
 */

/** The width the rail takes over from the bottom bar at, and the rail's own width. */
object CosmoNav {
    const val RAIL_FROM_DP = 600f
    const val RAIL_WIDTH_DP = 88f

    /** Whether a window [width] wide has the side rail (rather than the bottom bar). */
    fun usesRail(width: Dp): Boolean = width >= RAIL_FROM_DP.dp
}

/**
 * The mode's search entry: a round button beside the bottom bar's tabs (and at the head of the rail), not a tab of its own. It is lit
 * while [selected] (Search is open, and then no tab is).
 */
@Immutable
class CosmoNavSearch(val label: String, val selected: Boolean, val onClick: () -> Unit)

/**
 * A mode's navigation, whichever the window gets: under 600 dp, [content] fills the window and [bottomChrome] is drawn along its foot
 * with its tabs on (`showTabs` true); from 600 dp, [CosmoNavRail] stands at the start with the mode's [tabs], [search], [railHeader]
 * (the mark, the mode switch) and [railFooter] (Settings, the account), and [bottomChrome] is drawn with its tabs off, so only the
 * mini player runs along the foot of the page. [bottomChrome] is the mode's own [CosmoBottomChrome] call (its player, its notices):
 * it is given whether to show the tabs and the modifier that places it.
 *
 * The rail is [CosmoNav.RAIL_WIDTH_DP] wide; the page beside it measures the rest, so its own breakpoints see the page, not the
 * window. [windowWidth] is the width the decision is made on (a measured constraint, never the display's).
 */
@Composable
fun CosmoNavShell(
    windowWidth: Dp,
    tabs: List<CosmoTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    search: CosmoNavSearch? = null,
    railHeader: (@Composable () -> Unit)? = null,
    railFooter: (@Composable () -> Unit)? = null,
    bottomChrome: @Composable BoxScope.(showTabs: Boolean, placement: Modifier) -> Unit = { _, _ -> },
    content: @Composable BoxScope.() -> Unit,
) {
    val rail = CosmoNav.usesRail(windowWidth)
    Row(modifier.fillMaxSize()) {
        if (rail) CosmoNavRail(tabs, selectedIndex, onTabSelected, Modifier.fillMaxHeight(), search, railHeader, railFooter)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content()
            bottomChrome(!rail, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

/**
 * The side rail (the Music board's 9): 88 dp, on the page's ground with a hairline at its end. From the top: [header] (the mark), the
 * round [search] button, the [tabs] (each a 56 x 32 dp pill round its icon, solid ink when chosen, the name under it, a count as a
 * peach badge), and at the foot [footer]. It scrolls when the window is too short for it. Each item has the tab role in one group,
 * and a screen reader hears a tab's count with its name.
 */
@Composable
fun CosmoNavRail(
    tabs: List<CosmoTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    search: CosmoNavSearch? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val look = orbitLook()
    Row(modifier.width(CosmoNav.RAIL_WIDTH_DP.dp).testTag(NAV_RAIL_TAG)) {
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start))
                .verticalScroll(rememberScrollState())
                .padding(top = 14.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            header?.let { it(); Spacer(Modifier.height(14.dp)) }
            if (search != null) {
                CosmoSearchButton(search, size = 44.dp, look = look, fill = look.chip)
                Spacer(Modifier.height(18.dp))
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                tabs.forEachIndexed { index, tab -> RailItem(tab, index == selectedIndex && search?.selected != true, { onTabSelected(index) }, look) }
            }
            if (footer != null) {
                Spacer(Modifier.weight(1f).height(24.dp))
                footer()
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(look.ink.copy(alpha = if (look.dark) 0.08f else 0.09f)))
    }
}

@Composable
private fun RailItem(tab: CosmoTab, selected: Boolean, onClick: () -> Unit, look: OrbitLook) {
    val fonts = orbitFonts()
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) look.ink else Color.Transparent, tween(180), label = "rail-fill")
    val ink by animateColorAsState(if (selected) look.ground else look.dim, tween(180), label = "rail-ink")
    Column(
        Modifier.width(80.dp).clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = { haptics.play(Haptic.Select); onClick() })
            .semantics(mergeDescendants = true) { contentDescription = orbitTabName(tab.label, tab.count) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 56.dp, height = 32.dp)) {
            Box(
                Modifier.matchParentSize().cosmoFocusRing(interaction, 16.dp).clip(CircleShape).background(fill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            }
            val count = tab.count
            if (count != null && count > 0) OrbitBadge(count, look, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-6).dp), ring = look.ground)
        }
        Text(
            tab.label, style = fonts.chip.copy(fontSize = 12.sp), color = if (selected) look.ink else look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The round search button: beside the bottom bar's tabs at the bar's height on its fill, or at the head of the rail. Solid ink while
 * [CosmoNavSearch.selected].
 */
@Composable
internal fun CosmoSearchButton(search: CosmoNavSearch, size: Dp, look: OrbitLook, fill: Color, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val ground by animateColorAsState(if (search.selected) look.ink else fill, tween(180), label = "search-fill")
    val ink by animateColorAsState(if (search.selected) look.ground else look.dim, tween(180), label = "search-ink")
    Box(
        modifier.size(size).cosmoFocusRing(interaction, size / 2, floating = true).clip(CircleShape).background(ground)
            .selectable(selected = search.selected, interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = { haptics.play(Haptic.Select); search.onClick() })
            .semantics { contentDescription = search.label; selected = search.selected }
            .testTag(NAV_SEARCH_TAG),
        contentAlignment = Alignment.Center,
    ) { Icon(CosmoIcons.Search, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp)) }
}

/** The bottom bar's search button, at the tab bar's height on its fill. */
@Composable
internal fun DockSearchButton(search: CosmoNavSearch, modifier: Modifier = Modifier) {
    val look = orbitLook()
    CosmoSearchButton(search, size = com.cosmotune.ui.orbit.OrbitDockMetrics.BAR_DP.dp, look = look, fill = orbitDockFill(look), modifier = modifier)
}

const val NAV_RAIL_TAG = "nav:rail"
const val NAV_SEARCH_TAG = "nav:search"
