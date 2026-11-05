package com.cosmotune.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.recommendations.*
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.music_feedback_less_like_this
import com.cosmotune.music.sharedui.resources.music_feedback_show_again
import org.jetbrains.compose.resources.stringResource

/** A local preference action beside native song actions; source Like remains the existing heart. */
@Composable
fun MusicFeedbackActions(song: Song, onSubmitted: () -> Unit = {}) {
    if (song.isVideo || song.videoId.startsWith("podio:")) return
    val status by LocalPreferenceLearning.status.collectAsState()
    val hidden = remember(song, status.revision) { LocalPreferenceLearning.lessLikeThis(song) }
    TextButton(onClick = {
        LocalPreferenceLearning.onFeedback(song, if (hidden) MusicFeedback.CLEAR else MusicFeedback.LESS_LIKE_THIS)
        onSubmitted()
    }, enabled = status.ready && status.enabled && status.error == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("music:less-like-this")) {
        Icon(if (hidden) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.ThumbDown, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(if (hidden) Res.string.music_feedback_show_again else Res.string.music_feedback_less_like_this), modifier = Modifier.weight(1f))
    }
}
