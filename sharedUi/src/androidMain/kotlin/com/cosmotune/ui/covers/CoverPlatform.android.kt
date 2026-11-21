package com.cosmotune.ui.covers

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.io.ByteArrayOutputStream

/** Android's system photo picker: the person sees only their photos, and the app needs no permission to read the one chosen. */
@Composable internal actual fun rememberCoverPhotoChooser(onChosen: (Any) -> Unit): () -> Unit {
    val latest = rememberUpdatedState(onChosen)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { latest.value(it) } }
    return remember(launcher) { { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
}

internal actual fun encodeCoverJpeg(argb: IntArray, side: Int, quality: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(argb, side, side, Bitmap.Config.ARGB_8888)
    return try {
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)
            out.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

internal actual val coverPlaceHint: String = "Drag to move the photo and pinch to zoom."
