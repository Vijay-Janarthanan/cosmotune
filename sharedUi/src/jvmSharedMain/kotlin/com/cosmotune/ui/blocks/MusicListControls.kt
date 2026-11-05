package com.cosmotune.ui.blocks

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitActionRow
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.MusicListOrder
import com.cosmotune.music.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Same view controls on phone and desktop: one row of chips that scrolls sideways, so it never wraps (DESIGN_SYSTEM.md 4.4). */
@Composable
fun MusicListControls(shown: Int, loaded: Int, hasMore: Boolean, order: MusicListOrder,
    onOrder: (MusicListOrder) -> Unit, downloadedOnly: Boolean, onDownloadedOnly: (Boolean) -> Unit,
    onReset: (() -> Unit)?, modifier: Modifier = Modifier,
    selectionCount: Int = 0, selecting: Boolean = false, onSelecting: (() -> Unit)? = null,
    onPlaySelection: (() -> Unit)? = null, onMoreSelection: (() -> Unit)? = null,
    onSelectVisible: (() -> Unit)? = null, onClearSelection: (() -> Unit)? = null, hasAddedDates: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalCosmoColors.current.palette
    Column(modifier.fillMaxWidth().testTag("music:list:controls")) {
        KitActionRow(contentPadding = PaddingValues()) {
            Box {
                KitChip(stringResource(Res.string.music_list_sort, orderLabel(order)), selected = false, onClick = { expanded = true }, modifier = Modifier.testTag("music:list:order"), icon = CosmoIcons.Sort)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    MusicListOrder.entries.filter { hasAddedDates || it !in setOf(MusicListOrder.ADDED, MusicListOrder.ADDED_DESC) }.forEach { option ->
                        DropdownMenuItem(text = { Text(orderLabel(option)) }, onClick = { onOrder(option); expanded = false },
                            modifier = Modifier.semantics { selected = order == option },
                            trailingIcon = if (order == option) { { Icon(CosmoIcons.Check, null) } } else null)
                    }
                }
            }
            KitChip(stringResource(Res.string.downloaded), downloadedOnly, { onDownloadedOnly(!downloadedOnly) }, Modifier.testTag("music:list:downloaded"))
            if (onSelecting != null) KitChip(
                if (selecting) stringResource(Res.string.music_list_selected, selectionCount) else stringResource(Res.string.music_list_select),
                selecting, onSelecting, Modifier.testTag("music:list:select"),
            )
            if (onReset != null) KitTextButton(stringResource(Res.string.music_list_reset), onReset)
        }
        Text(stringResource(if (hasMore) Res.string.music_list_loaded_count else Res.string.music_list_count, shown, loaded),
            style = LocalCosmoType.current.meta, color = Color(palette.textSecondary),
            modifier = Modifier.padding(bottom = 4.dp))
        if (selecting) KitActionRow(contentPadding = PaddingValues()) {
            if (onSelectVisible != null) KitTextButton(stringResource(Res.string.music_list_select_shown), onSelectVisible)
            if (selectionCount > 0) {
                if (onPlaySelection != null) KitButton(stringResource(Res.string.music_list_play_selected), onPlaySelection, kind = KitButtonKind.TONAL, dense = true)
                if (onMoreSelection != null) KitTextButton(stringResource(Res.string.more), onMoreSelection)
                if (onClearSelection != null) KitTextButton(stringResource(Res.string.music_list_clear_selection), onClearSelection)
            }
        }
    }
}

@Composable private fun orderLabel(order: MusicListOrder) = stringResource(when (order) {
    MusicListOrder.DEFAULT -> Res.string.music_list_default
    MusicListOrder.REVERSED -> Res.string.music_list_reversed
    MusicListOrder.TITLE -> Res.string.music_list_title
    MusicListOrder.TITLE_DESC -> Res.string.music_list_title_desc
    MusicListOrder.ARTIST -> Res.string.music_list_artist
    MusicListOrder.ALBUM -> Res.string.music_list_album
    MusicListOrder.DURATION -> Res.string.music_list_duration
    MusicListOrder.ADDED -> Res.string.music_list_added
    MusicListOrder.ADDED_DESC -> Res.string.music_list_added_desc
})
