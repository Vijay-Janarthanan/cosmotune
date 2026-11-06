package com.cosmotune.music.ui.player

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cosmotune.music.R
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.music.ui.replay.cacheForSharing
import com.cosmotune.music.ui.replay.saveToGallery
import com.cosmotune.music.ui.replay.sendIntent
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/**
 * The picked lines, drawn, with the two things you can do to the picture.
 *
 * Built on [PlayerDrawer] rather than on a `ModalBottomSheet` for the same reason
 * as every other sheet in this package: it is inside the player, which is itself
 * already a modal sheet, and nesting the two leaves the inner one with nothing
 * to be modal over. It also means this drawer gets the player's own Orbit sheet,
 * the drag-to-dismiss gesture and the scrim fade for free; its insides are the
 * shared [LyricsShareDrawer], so the phone and the render proofs draw one sheet.
 *
 * The render happens on a background dispatcher and the preview is a plain
 * `Image` of the result — no second layout pass, no offscreen Compose, and the
 * identical pixels that the Save and Share buttons then write to disk.
 */
@Composable
internal fun AndroidLyricsShareSheet(
    hazeState: HazeState,
    request: LyricsShareRequest,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var image by remember(request) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    // Same picture, cut to the 9:16 frame a story wants instead of to its lines.
    var story by remember(request) { mutableStateOf(false) }

    LaunchedEffect(request, story) {
        image = null
        failed = false
        saved = false
        image = runCatching { renderLyricsShareCard(context, request, story) }
            .onFailure { failed = true }
            .getOrNull()
    }

    // The whole song's lyrics as a standard .lrc file, saved where the listener picks.
    val lrc = remember(request) { request.lrcText }
    var lrcSaved by remember(request) { mutableStateOf(false) }
    val lrcTarget = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null && lrc != null) scope.launch {
            lrcSaved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(lrc.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            if (!lrcSaved) com.cosmotune.music.ui.player.PlayerPlatform.host.showMessage("The lyrics could not be saved")
        }
    }

    // A short MP4 of the chosen lines lighting up over the cover, with that stretch of the song (LyricsVideoExport).
    val videoPlan = remember(request) {
        com.cosmotune.lyrics.video.LyricsVideoPlan.forSelection(request.lrcLines, request.lines.filterNot { it.isGap }.map { it.text })
    }
    var videoProgress by remember(request) { mutableStateOf<Float?>(null) }
    var videoSaved by remember(request) { mutableStateOf(false) }
    val videoTarget = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri ->
        val plan = videoPlan
        if (uri != null && plan != null) scope.launch {
            videoProgress = 0f
            val temporary = java.io.File(context.cacheDir, "lyrics-video.mp4")
            videoSaved = runCatching {
                LyricsVideoExport.export(context, request, plan, temporary) { videoProgress = it }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> temporary.inputStream().use { it.copyTo(out) } }
                }
            }.isSuccess
            temporary.delete()
            videoProgress = null
            if (!videoSaved) com.cosmotune.music.ui.player.PlayerPlatform.host.showMessage("The lyrics video could not be made")
        }
    }

    val savedLabel = stringResource(R.string.saved_bang)
    val bitmap = image
    val extras = buildList {
        add(
            LyricsShareAction(
                label = stringResource(if (story) R.string.lyrics_share_story_revert else R.string.lyrics_share_story),
                icon = if (story) Icons.Rounded.Undo else Icons.Rounded.PhoneIphone,
                enabled = image != null,
            ) {
                haptics.play(Haptic.Tap)
                story = !story
            },
        )
        if (lrc != null) add(
            LyricsShareAction(label = "Save as .lrc", icon = Icons.Rounded.Download, done = lrcSaved, doneLabel = savedLabel) {
                haptics.play(Haptic.Tap)
                lrcTarget.launch(request.lrcFileName)
            },
        )
        if (videoPlan != null) add(
            LyricsShareAction(
                label = videoProgress?.let { "Making the video… ${(it * 100).toInt()}%" } ?: "Save as video",
                icon = Icons.Rounded.Download,
                enabled = videoProgress == null,
                done = videoSaved,
                doneLabel = savedLabel,
            ) {
                haptics.play(Haptic.Tap)
                videoTarget.launch(request.lrcFileName.substringBeforeLast('.') + ".mp4")
            },
        )
    }
    val pair = listOf(
        // Once the picture is in the gallery the button says so itself — a
        // check and "Saved!" — so the line under the card does not have to.
        LyricsShareAction(
            label = stringResource(R.string.save),
            icon = Icons.Rounded.Download,
            enabled = image != null,
            done = saved,
            doneLabel = savedLabel,
        ) {
            val drawn = image
            if (drawn != null) {
                haptics.play(Haptic.Tap)
                scope.launch {
                    saved = saveToGallery(
                        context,
                        drawn,
                        request.song.title,
                        LYRICS_FILE_PREFIX,
                    )
                }
            }
        },
        LyricsShareAction(
            label = stringResource(R.string.share),
            icon = Icons.Rounded.IosShare,
            enabled = image != null,
            primary = true,
        ) {
            val drawn = image
            if (drawn != null) {
                haptics.play(Haptic.Tap)
                scope.launch {
                    val uri = cacheForSharing(context, drawn, LYRICS_FILE_NAME)
                    if (uri == null) {
                        failed = true
                        saved = false
                    } else {
                        context.startActivity(
                            Intent.createChooser(
                                sendIntent(uri),
                                context.getString(R.string.share_your_lyrics),
                            ),
                        )
                    }
                }
            }
        },
    )
    val cardDescription = stringResource(R.string.your_lyrics_card)
    val ink = orbitLook().ink

    LyricsShareDrawer(
        hazeState = hazeState,
        title = stringResource(R.string.lyrics_share_title),
        description = stringResource(R.string.lyrics_share_description),
        // The card is only as tall as its lines, so the frame follows it.
        ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (9f / 16f),
        extras = extras,
        pair = pair,
        onDismiss = onDismiss,
        modifier = modifier,
        error = if (failed) stringResource(R.string.couldnt_draw_picture) else null,
    ) { frame ->
        if (bitmap == null) {
            Box(modifier = frame, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = ink,
                )
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = cardDescription,
                modifier = frame,
            )
        }
    }
}

/**
 * Two names, deliberately not the Replay's.
 *
 * The cache name so a replay share and a lyrics share cannot overwrite each
 * other while the receiving app is still reading the first, and the gallery
 * prefix so a saved card in the pictures folder says which feature produced it.
 */
private const val LYRICS_FILE_NAME = "lyrics.png"
private const val LYRICS_FILE_PREFIX = "cosmotune-lyrics"
