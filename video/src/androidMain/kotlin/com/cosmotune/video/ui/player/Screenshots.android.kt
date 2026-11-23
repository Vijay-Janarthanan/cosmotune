package com.cosmotune.video.ui.player

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.cosmotune.video.player.VideoPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The phone and the TV: the picture is copied from the video's surface (the largest `SurfaceView` on screen; the engine draws the video
 * into one) and saved to Pictures/Cosmotune through the media store, where the gallery finds it.
 */
@Composable
internal actual fun rememberVideoScreenshots(playback: VideoPlayback): VideoScreenshots? {
    val view = LocalView.current
    return remember(playback, view) {
        object : VideoScreenshots {
            override suspend fun take(title: String, positionMs: Long): Boolean {
                val surface = withContext(Dispatchers.Main) { largestSurface(view.rootView) } ?: return false
                val bitmap = copy(surface) ?: return false
                return withContext(Dispatchers.IO) { runCatching { save(view, bitmap, screenshotName(title, positionMs)) }.getOrDefault(false) }
            }
        }
    }
}

private fun largestSurface(root: View): SurfaceView? {
    var best: SurfaceView? = null
    fun walk(v: View) {
        if (v is SurfaceView && v.isShown && v.width > 0 && v.height > 0 && v.holder.surface?.isValid == true) {
            if (best == null || v.width * v.height > best!!.width * best!!.height) best = v
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(root)
    return best
}

private suspend fun copy(surface: SurfaceView): Bitmap? = suspendCancellableCoroutine { done ->
    val bitmap = runCatching { Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888) }.getOrNull()
    if (bitmap == null) {
        done.resumeWith(Result.success(null))
        return@suspendCancellableCoroutine
    }
    try {
        PixelCopy.request(surface, bitmap, { result ->
            if (done.isActive) done.resumeWith(Result.success(if (result == PixelCopy.SUCCESS) bitmap else null))
        }, Handler(Looper.getMainLooper()))
    } catch (e: IllegalArgumentException) {
        if (done.isActive) done.resumeWith(Result.success(null))
    }
}

private fun save(view: View, bitmap: Bitmap, name: String): Boolean {
    val context = view.context
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cosmotune")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        if (!ok) resolver.delete(uri, null, null)
        return ok
    }
    // Android 8 and 9: the app's own Pictures folder (no storage permission needed).
    val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Cosmotune").apply { mkdirs() }
    return FileOutputStream(File(folder, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
