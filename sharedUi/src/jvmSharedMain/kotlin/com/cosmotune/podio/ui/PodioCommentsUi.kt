package com.cosmotune.podio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.PodioComment
import com.cosmotune.podio.PodioComments
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBusyRing
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.rememberUiCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * An episode's comments: the replies to the post its show made for it on the fediverse ([PodioComments]). They load when asked,
 * since that reaches another server; the thread itself opens in the browser too.
 */
@Composable internal fun EpisodeComments(thread: String, fonts: OrbitFonts, look: OrbitLook) {
    val uri = LocalUriHandler.current
    val scope = rememberUiCoroutineScope()
    var comments by remember(thread) { mutableStateOf<List<PodioComment>?>(null) }
    var loading by remember(thread) { mutableStateOf(false) }
    var error by remember(thread) { mutableStateOf<String?>(null) }
    val readable = PodioComments.contextUrl(thread) != null
    Column(Modifier.fillMaxWidth().testTag("podio:episode:comments"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbitLabel(comments?.let { "Comments · ${it.size}" } ?: "Comments", fonts, look.dim)
        OrbitChipRow(0.dp) {
            if (readable && comments == null) OrbitSelectChip(if (loading) "Loading…" else "Show comments", false, fonts, look, {
                if (!loading) scope.launch {
                    loading = true; error = null
                    try { comments = PodioComments.fetch(thread) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Couldn't load the comments. Open the thread instead, or retry." }
                    finally { loading = false }
                }
            }, icon = CosmoIcons.List, haptic = false, modifier = Modifier.testTag("podio:episode:comments:load"))
            OrbitSelectChip("Open the thread", false, fonts, look, { PodioCatalogue.httpUrl(thread)?.let { runCatching { uri.openUri(it) } } },
                icon = CosmoIcons.Share, haptic = false)
            if (loading) OrbitBusyRing(size = 20.dp)
        }
        error?.let { Text(it, style = fonts.body.copy(fontSize = 13.sp), color = look.dim) }
        comments?.let { list ->
            if (list.isEmpty()) Text("No replies yet.", style = fonts.body.copy(fontSize = 13.sp), color = look.dim)
            list.forEach { comment -> CommentRow(comment, fonts, look) }
        }
    }
}

@Composable private fun CommentRow(comment: PodioComment, fonts: OrbitFonts, look: OrbitLook) {
    Column(Modifier.fillMaxWidth().padding(start = (comment.depth * 16).dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(comment.author.ifBlank { comment.handle }, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            val day = PodioFormat.date(comment.published)
            if (day.isNotBlank()) Text(day, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, maxLines = 1)
        }
        Text(comment.text, style = fonts.body.copy(fontSize = 14.sp, lineHeight = 19.sp), color = look.ink)
    }
}
