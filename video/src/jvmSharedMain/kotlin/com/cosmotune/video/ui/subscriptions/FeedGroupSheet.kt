package com.cosmotune.video.ui.subscriptions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitCheckRow
import com.cosmotune.ui.orbit.OrbitFormField
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoSheetHeading
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.feed.FeedChannelGroup
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_group_all
import com.cosmotune.video.resources.video_group_channels
import com.cosmotune.video.resources.video_group_delete
import com.cosmotune.video.resources.video_group_edit
import com.cosmotune.video.resources.video_group_name
import com.cosmotune.video.resources.video_group_name_hint
import com.cosmotune.video.resources.video_group_new
import com.cosmotune.video.resources.video_group_save
import org.jetbrains.compose.resources.stringResource

/**
 * The feed's group chips: All, the listener's groups, then "New group" (and "Edit group" while one is picked). The row scrolls
 * sideways when the chips do not fit, so none is cut off.
 */
@Composable
internal fun FeedGroupChips(
    groups: List<FeedChannelGroup>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
    onEdit: (FeedChannelGroup) -> Unit,
    inset: Dp,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = CosmoSpacing.margin + inset, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OrbitSelectChip(stringResource(Res.string.video_group_all), selectedId == null, fonts, look, onClick = { onSelect(null) })
        for (group in groups) {
            OrbitSelectChip(group.name, group.id == selectedId, fonts, look, onClick = { onSelect(group.id) })
        }
        groups.firstOrNull { it.id == selectedId }?.let { picked ->
            OrbitSelectChip(stringResource(Res.string.video_group_edit), false, fonts, look, onClick = { onEdit(picked) }, icon = CosmoIcons.Settings)
        }
        OrbitSelectChip(stringResource(Res.string.video_group_new), false, fonts, look, onClick = onNew, icon = CosmoIcons.Plus)
    }
}

/** Makes or changes a group: its name and the followed channels in it; Delete when it already exists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedGroupSheet(
    group: FeedChannelGroup,
    isNew: Boolean,
    channels: List<Subscription>,
    onSave: (FeedChannelGroup) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var name by remember(group.id) { mutableStateOf(group.name) }
    var picked by remember(group.id) { mutableStateOf(group.channelIds) }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CosmoSheetHeading(stringResource(if (isNew) Res.string.video_group_new else Res.string.video_group_edit))
        Column(Modifier.padding(horizontal = CosmoSpacing.margin)) {
            OrbitFormField(
                value = name,
                onValueChange = { name = it.take(40) },
                placeholder = stringResource(Res.string.video_group_name_hint),
                label = stringResource(Res.string.video_group_name),
            )
            Spacer(Modifier.height(16.dp))
            OrbitLabel(stringResource(Res.string.video_group_channels, picked.size), fonts, look.dim)
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.heightIn(max = windowHeight * 0.45f).padding(horizontal = CosmoSpacing.margin)) {
            items(channels, key = { it.channelId }) { channel ->
                OrbitCheckRow(
                    title = channel.name,
                    checked = channel.channelId in picked,
                    onChange = { on -> picked = if (on) picked + channel.channelId else picked - channel.channelId },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
        ) {
            if (!isNew) OrbitActionPill(stringResource(Res.string.video_group_delete), onDelete, fonts, look)
            OrbitActionPill(
                stringResource(Res.string.video_group_save),
                { onSave(group.copy(name = name, channelIds = picked)) },
                fonts,
                look,
                primary = true,
                enabled = name.isNotBlank(),
            )
        }
    }
}
