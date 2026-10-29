package com.cosmotune.video.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.ui.PendingVideo

/**
 * Reads the intent of a video file that another app opens with Cosmotune ("Open with" from a file manager, a gallery, a download):
 * [read] answers the video for Video's local player, or null when the intent is anything else. [PhoneModeHost] calls it for the
 * activity's launch intent and for every later one, so the activity does not need to know of Video.
 *
 * The intent is marked read ([EXTRA_CONSUMED]), so the activity being recreated (a theme change) does not play the file again.
 */
object LocalVideoLink {
    private const val EXTRA_CONSUMED = "cosmotune.localVideoConsumed"

    /** The video [intent] asks for: its address (`content://` or `file://`) as the local source, and its name when the provider says it. */
    fun read(context: Context, intent: Intent?): PendingVideo? {
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false) || intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val name = displayName(context, uri)
        val type = intent.type ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val isVideo = type?.startsWith("video/") == true || LocalVideo.isVideoName(name ?: LocalVideo.fileName(uri.toString()))
        if (!isVideo) return null
        intent.putExtra(EXTRA_CONSUMED, true)
        return PendingVideo(videoId = "", startMs = null, localSource = uri.toString(), localName = name)
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getString(column) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
