package com.cosmotune.video.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoSheetHeading
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_filter_add
import com.cosmotune.video.resources.video_filter_broken
import com.cosmotune.video.resources.video_filter_hidden_channels
import com.cosmotune.video.resources.video_filter_likes
import com.cosmotune.video.resources.video_filter_likes_any
import com.cosmotune.video.resources.video_filter_none_channels
import com.cosmotune.video.resources.video_filter_note
import com.cosmotune.video.resources.video_filter_remove
import com.cosmotune.video.resources.video_filter_word_hint
import com.cosmotune.video.resources.video_filter_words
import com.cosmotune.video.resources.video_set_filters
import com.cosmotune.video.select.ContentFilter
import com.cosmotune.video.settings.VideoSettings
import org.jetbrains.compose.resources.stringResource

/** The like floors the comments filter offers: none, then 1, 5, 10, 50, 100 likes. */
internal val COMMENT_LIKE_FLOORS = listOf(0L, 1L, 5L, 10L, 50L, 100L)

/**
 * Settings, Content, Filters: the words (a word, or `/an expression/`) whose videos and comments are hidden everywhere, the
 * channels hidden everywhere (added from a video's menu, "Hide this channel"; removed here), and the like floor for comments.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ContentFilterSheet(settings: VideoSettings, update: ((VideoSettings) -> VideoSettings) -> Unit, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var typed by remember { mutableStateOf("") }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val add = {
        val word = typed.trim()
        if (word.isNotEmpty()) update { it.copy(hiddenWords = ContentFilter.cleanWords(it.hiddenWords + word)) }
        typed = ""
    }
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CosmoSheetHeading(stringResource(Res.string.video_set_filters))
        Column(
            Modifier
                .heightIn(max = windowHeight * 0.7f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CosmoSpacing.margin),
        ) {
            Text(stringResource(Res.string.video_filter_note), style = fonts.body, color = look.dim)
            Spacer(Modifier.height(16.dp))
            OrbitLabel(stringResource(Res.string.video_filter_words), fonts, look.dim)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitInputField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = stringResource(Res.string.video_filter_word_hint),
                    fonts = fonts,
                    look = look,
                    modifier = Modifier.weight(1f),
                    error = ContentFilter.isBrokenPattern(typed.trim()),
                )
                OrbitActionPill(stringResource(Res.string.video_filter_add), add, fonts, look, primary = true, enabled = typed.isNotBlank())
            }
            if (ContentFilter.isBrokenPattern(typed.trim())) {
                Text(stringResource(Res.string.video_filter_broken), style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            }
            if (settings.hiddenWords.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (word in settings.hiddenWords) {
                        OrbitSelectChip(
                            title = word,
                            selected = true,
                            fonts = fonts,
                            look = look,
                            icon = CosmoIcons.Close,
                            onClick = { update { it.copy(hiddenWords = it.hiddenWords - word) } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            OrbitLabel(stringResource(Res.string.video_filter_hidden_channels), fonts, look.dim)
            Spacer(Modifier.height(8.dp))
            if (settings.hiddenChannels.isEmpty()) {
                Text(stringResource(Res.string.video_filter_none_channels), style = fonts.body, color = look.dim)
            } else {
                for ((id, name) in settings.hiddenChannels) {
                    Row(
                        Modifier
                            .padding(bottom = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(look.row)
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name.ifBlank { id }, style = fonts.title, color = look.ink, modifier = Modifier.weight(1f), maxLines = 1)
                        OrbitRoundButton(
                            icon = CosmoIcons.Close,
                            label = stringResource(Res.string.video_filter_remove),
                            ink = look.ink,
                            onClick = { update { it.copy(hiddenChannels = it.hiddenChannels - id) } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            OrbitLabel(stringResource(Res.string.video_filter_likes), fonts, look.dim)
            Spacer(Modifier.height(8.dp))
            val any = stringResource(Res.string.video_filter_likes_any)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (floor in COMMENT_LIKE_FLOORS) {
                    OrbitSelectChip(
                        title = if (floor == 0L) any else "$floor+",
                        selected = settings.commentMinLikes == floor,
                        fonts = fonts,
                        look = look,
                        onClick = { update { it.copy(commentMinLikes = floor) } },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
