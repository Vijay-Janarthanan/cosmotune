package com.cosmotune.listening

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.cosmotune.music.data.model.Song
import java.io.FileNotFoundException

/** MediaStore/SAF grants stay on the phone; an audit never resolves an online stream. */
class PhoneLibraryAccess(context: Context) : LocalLibraryAccess {
    private val resolver = context.applicationContext.contentResolver

    override fun inspect(song: Song): LocalLibraryFile {
        try { return FileLibraryAccess.inspect(song) }
        catch (e: Exception) {
            val uri = song.localUri?.let(Uri::parse)?.takeIf { it.scheme == "content" } ?: throw e
            val descriptor = resolver.openAssetFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("The audio location is unavailable")
            val length = descriptor.use { it.length.takeIf { value -> value >= 0 } }
            val modified = if (uri.authority == "media") runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            }.getOrNull() else null
            return LocalLibraryFile(uri.normalizeScheme().toString(), length, modified) {
                resolver.openInputStream(uri) ?: throw FileNotFoundException("The audio location is unavailable")
            }
        }
    }
}
