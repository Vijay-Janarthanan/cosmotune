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
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType

/**
 * A page with nothing to show (an empty library, no results, no downloads): a quiet [icon], a light [title], the [message] in
 * secondary ink, and one action when [actionLabel] and [onAction] are given. Centred, no container.
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
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(40.dp))
        Text(title, style = type.titleMedium, color = Color(palette.textPrimary), textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = type.bodyText, color = Color(palette.textSecondary), textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(6.dp))
            KitButton(actionLabel, onAction, kind = KitButtonKind.TONAL)
        }
    }
}

/** Something failed (the network, a source): [KitEmptyState] with a retry. */
@Composable
fun KitErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, title: String = "Something went wrong", retryLabel: String = "Try again") {
    KitEmptyState(title = title, message = message, modifier = modifier, icon = CosmoIcons.Replay, actionLabel = retryLabel, onAction = onRetry)
}

/** A thin offline notice at the top of a page: a dot and one line, on `surface`, never blocking the page under it. */
@Composable
fun KitOfflineBanner(modifier: Modifier = Modifier, text: String = "You're offline. Showing what's saved on this device.") {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 6.dp).clip(RoundedCornerShape(CosmoRadii.rMd)).background(Color(palette.surface)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(palette.danger)))
        Spacer(Modifier.width(10.dp))
        Text(text, style = type.meta, color = Color(palette.textSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** [count] skeleton media rows, in the shape [KitMediaRow] will take (art and two lines), while a list loads. Still under reduced motion. */
@Composable
fun KitSkeletonRows(count: Int = 6, modifier: Modifier = Modifier, art: KitArt = KitArt.SQUARE) {
    Column(modifier.fillMaxWidth()) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = CosmoSpacing.margin, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val height = if (art == KitArt.WIDE) 56.dp else 48.dp
                CosmoShimmer(Modifier.size(height * art.ratio, height), if (art == KitArt.ROUND) CircleShape else RoundedCornerShape(CosmoRadii.rSm))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CosmoShimmer(Modifier.fillMaxWidth(0.7f).height(12.dp))
                    CosmoShimmer(Modifier.fillMaxWidth(0.45f).height(10.dp))
                }
            }
        }
    }
}

/**
 * The desktop's navigation rail (the dock's tabs, standing on the start edge of a wide window): the same [CosmoTab]s, the chosen
 * one on the neutral `surface3` pill, every one labelled.
 */
@Composable
fun KitNavRail(tabs: List<CosmoTab>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, header: @Composable ColumnScope.() -> Unit = {}) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(modifier.widthIn(min = 88.dp).padding(vertical = 12.dp).selectableGroup(), horizontalAlignment = Alignment.CenterHorizontally) {
        header()
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val ink = Color(if (selected) palette.textPrimary else palette.textSecondary)
            Column(
                Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(CosmoRadii.rMd))
                    .background(if (selected) Color(palette.surfaceVariant) else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .wrapContentWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(2.dp))
                Text(tab.label, style = type.tabLabel, color = ink, maxLines = 1)
            }
        }
    }
}

/**
 * A bottom sheet of the kit: the bridge's detached glass sheet ([com.cosmotune.ui.bridge.ModalBottomSheet]) with an optional
 * light [title]. Its rows are [KitSettingsRow]s or [KitMediaRow]s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    com.cosmotune.ui.bridge.ModalBottomSheet(onDismissRequest = onDismiss) {
        if (!title.isNullOrBlank()) {
            Text(title, style = type.titleLarge, color = Color(palette.textPrimary), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
        }
        content()
    }
}
