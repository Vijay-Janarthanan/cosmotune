package com.cosmotune.music.ui.replay

import com.cosmotune.music.R

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import com.cosmotune.ui.bridge.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cosmotune.music.data.stats.ReplaySummary
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.replay.OrbitReplayShareBody
import com.cosmotune.ui.replay.OrbitReplayShareNote
import com.cosmotune.ui.theme.CosmoRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * The picture, and the apps that can take it.
 *
 * Deliberately not the system chooser on its own. A chooser is a modal list of
 * app names with no sight of what is about to be sent, and what is about to be
 * sent here is the entire point — nobody shares a Replay they have not looked
 * at. So the picture is the sheet, and Save and Share sit under it; Share hands
 * the picture to the system's own chooser.
 *
 * The body of an Orbit sheet ([OrbitReplayShareBody]); its host is the Orbit
 * sheet, [com.cosmotune.ui.sheets.SurfaceSheetSwitch], which paints the ground.
 */
@Composable
fun ReplayShareSheet(
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
    /** The card being sent, or null for the whole Replay. */
    page: ReplayStoryPage?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var poster by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(summary, page) {
        poster = runCatching { renderReplayPoster(context, summary, holder, memberSince, page) }
            .onFailure { failed = true }
            .getOrNull()
    }

    // Two buttons, not a row of app icons.
    //
    // Resolving the apps that accept an image and drawing their launcher icons
    // was the first version. It looked like a share sheet because it was
    // imitating one — and the system already has a share sheet, kept up to date,
    // ordered by what this user actually shares to, and reachable in one tap.
    //
    // Disabled rather than hidden while the poster renders: buttons that appear
    // a second after the sheet does are buttons that get tapped at exactly the
    // moment they move. The picture is kept small enough that both are on
    // screen with it.
    OrbitReplayShareBody(
        overline = stringResource(R.string.replay),
        title = stringResource(R.string.share_my_replay),
        description = if (page == null) {
            stringResource(R.string.replay_share_summary_description)
        } else {
            stringResource(R.string.replay_share_card_description)
        },
        saveLabel = if (saved) stringResource(R.string.saved) else stringResource(R.string.save),
        saved = saved,
        onSave = {
            poster?.let { image -> scope.launch { saved = saveToGallery(context, image, summary.label) } }
        },
        shareLabel = stringResource(R.string.share),
        onShare = {
            poster?.let { image ->
                scope.launch {
                    val uri = cacheForSharing(context, image) ?: return@launch
                    context.startActivity(
                        Intent.createChooser(sendIntent(uri), context.getString(R.string.share_your_replay)),
                    )
                    onDismiss()
                }
            }
        },
        ready = poster != null,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        val image = poster
        when {
            image != null -> Image(
                bitmap = image.asImageBitmap(),
                contentDescription = stringResource(R.string.your_replay),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
            failed -> OrbitReplayShareNote(stringResource(R.string.couldnt_draw_picture))
            else -> CircularProgressIndicator(color = orbitLook().dim)
        }
    }
}

@Composable
internal fun ShareAction(
    label: String,
    icon: ImageVector,
    /** The one that sends it, which is the one that should be reached for. */
    accent: Boolean,
    enabled: Boolean,
    /**
     * The picture has been written out: the button turns into a check and says
     * so, rather than sitting there offering to do it again.
     */
    saved: Boolean = false,
    /** What it says once [saved]; falls back to [label] when null. */
    savedLabel: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = when {
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        accent -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(CosmoRadii.rMd))
            .background(background.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            // The sides carried nothing but the label's own advance, so the
            // words sat against the pill's edges; the vertical rhythm is the
            // one the Replay's buttons use.
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The icon and the word crossfade rather than swap on a frame, so the
        // change reads as the button settling into its new state instead of
        // flickering between two unrelated ones.
        Crossfade(targetState = saved, label = "shareAction") { isSaved ->
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isSaved) Icons.Rounded.Check else icon,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (isSaved) (savedLabel ?: label) else label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W700,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun sendIntent(uri: Uri) = Intent(Intent.ACTION_SEND)
    .setType(MIME)
    .putExtra(Intent.EXTRA_STREAM, uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/**
 * Writes the poster somewhere another app can read it.
 *
 * The app's own cache, exposed through a [FileProvider] rather than by handing
 * out a `file://` path: that has been illegal since API 24, and a content URI is
 * what lets the read grant travel with the intent and expire with it — the other
 * app gets this one picture and nothing else in the folder.
 *
 * Shared with the lyrics card, which writes under its own [fileName] so the two
 * hand-offs can't overwrite each other while the receiving app is still reading.
 */
internal suspend fun cacheForSharing(
    context: Context,
    bitmap: Bitmap,
    fileName: String = "replay.png",
): Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
        // One name, overwritten: the folder is a hand-off point, not an album,
        // and a file per share would accumulate megabytes nobody ever looks at.
        val file = File(folder, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

/**
 * Saves the poster to the device's pictures, where a gallery will find it.
 *
 * Through MediaStore, which from API 29 needs no permission at all for a row the
 * app created. Below that it writes into the public Pictures folder directly,
 * which is why the legacy branch exists — and why saving is offered rather than
 * assumed: on an older device it can fail on a permission this app doesn't ask
 * for until a download is started.
 */
internal suspend fun saveToGallery(
    context: Context,
    bitmap: Bitmap,
    label: String,
    prefix: String = "cosmotune-replay",
): Boolean = withContext(Dispatchers.IO) {
    val name = "$prefix-${label.replace(' ', '-').lowercase(Locale.ROOT)}.png"
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Cosmotune",
                )
            }
        }
        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("no row")
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } ?: error("no stream")
        true
    }.getOrDefault(false)
}

private const val MIME = "image/png"
private const val SHARE_FOLDER = "shared"
