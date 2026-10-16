package com.cosmotune.video.ui.comments

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_comments
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.stringResource

/*
 * The two comment composables the watch page (Y-19) and the Shorts pager (Y-31) call. Plan task Y-23 replaces their bodies with
 * the comments of spec 5.9 and keeps their signatures; until then the panel is empty and the tile only says "Comments".
 */

/**
 * The comments of [videoId] (spec 5.9): as a sheet under the player when [sheet] (the phone; [onDismiss] closes it), or as the
 * list of the desktop's side column. The caller places it with [modifier]. Stub: an empty panel (Y-23).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CommentsPanel(videoId: String, sheet: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier)
}

/**
 * The watch page's comments tile (spec 5.4: the first comment's avatar and two lines; hidden when comments are off); [onOpen] opens
 * the [CommentsPanel]. Stub: a row that says "Comments" (Y-23).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CommentsPreviewTile(videoId: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    CosmoListRow(
        title = stringResource(Res.string.video_comments),
        onClick = onOpen,
        modifier = modifier,
        leading = CosmoRowLeading.Icon(VideoIcons.Comment),
    )
}
