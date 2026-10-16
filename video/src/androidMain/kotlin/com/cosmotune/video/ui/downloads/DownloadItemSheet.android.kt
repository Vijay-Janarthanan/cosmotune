package com.cosmotune.video.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.video.downloads.android.SaveCopyToMovies

/** Android: "Save a copy to Movies" is the download engine's `SaveCopyToMovies` (through `MediaStore`, no permission asked). */
@Composable
internal actual fun rememberSaveCopy(): SaveCopy? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val copier = SaveCopyToMovies(context)
        SaveCopy { entry -> copier.save(entry) }
    }
}
