package com.cosmotune.music.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.cosmotune.listening.SongFileName
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share the file itself (the idea from Kreate, see NOTICE.md): a song kept on the phone (a local file or a download) is copied,
 * under a readable name, into the cache folder the FileProvider may hand out, and offered to the share sheet. Only the one copy
 * is kept there; the previous one is removed first.
 */
object LocalFileShare {
    private const val MAX_BYTES = 1_000L * 1024 * 1024

    /** Opens the share sheet with the song's own file; false when there is no readable file. */
    suspend fun share(context: Context, song: Song): Boolean {
        val source = song.localUri?.let(Uri::parse) ?: song.localPath?.let { Uri.fromFile(File(it)) } ?: return false
        val resolver = context.contentResolver
        val type = runCatching { resolver.getType(source) }.getOrNull() ?: "audio/*"
        val shared = withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(context.cacheDir, "shared").apply { mkdirs() }
                folder.listFiles()?.filter { it.name.startsWith(SongFileName.PREFIX) }?.forEach { it.delete() }
                val target = File(folder, SongFileName.of(song.artist, song.title, SongFileName.extension(source.lastPathSegment, type)))
                val input = if (source.scheme == "file") File(requireNotNull(source.path)).inputStream() else requireNotNull(resolver.openInputStream(source))
                input.use { stream ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            check(total <= MAX_BYTES) { "too large to share" }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            }.getOrNull()
        } ?: return false
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, shared)
            .putExtra(Intent.EXTRA_TITLE, "${song.title} · ${song.artist}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share the file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
