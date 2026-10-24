package com.cosmotune.music.download

import android.content.Context
import android.net.Uri
import com.cosmotune.music.data.downloads.ManifestDownloadTransport
import com.cosmotune.music.data.downloads.OfflineHlsManifest
import java.io.File

/**
 * An offline copy of an unencrypted HLS media playlist.  This deliberately
 * keeps fMP4 samples untouched: Media3's HLS reader is the same one that
 * plays the network stream, and therefore supports codecs MediaMuxer cannot
 * put in a fresh MP4 (notably FLAC and Dolby variants).
 */
internal object OfflineHls {
    suspend fun save(
        context: Context,
        id: String,
        url: String,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit,
        lyrics: LyricsTag.Embeddable?,
        artwork: MediaTagger.Artwork?,
    ): Uri = ManifestDownloadTransport.run(url, headers) { transfer ->
        val playlist = transfer.read(url, ManifestDownloadTransport.MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        val plan = OfflineHlsManifest.parse(playlist, requireInitialization = false)
        val references = (listOfNotNull(plan.initialization) + plan.media).distinct()
        OfflineMusicPackage.build(context, id) { root ->
            val localNames = linkedMapOf<String, String>()
            references.forEachIndexed { index, remote ->
                transfer.checkCancelled()
                val local = "segment-${index.toString().padStart(5, '0')}.m4s"
                java.io.FileOutputStream(File(root, local)).use { output ->
                    transfer.copy(transfer.resolve(remote), output); output.fd.sync()
                }
                localNames[remote] = local
                onProgress(index + 1L, references.size.toLong())
            }
            val localPlaylist = playlist
                .replace(Regex("""(URI=")([^"]+)(")""")) { match ->
                    match.groupValues[1] + requireNotNull(localNames[match.groupValues[2]]) + match.groupValues[3]
                }
                .lineSequence().joinToString("\n") { line -> localNames[line.trim()] ?: line }
            OfflineMusicPackage.write(File(root, "playlist.m3u8"), localPlaylist.toByteArray(Charsets.UTF_8))
            OfflineMusicPackage.extras(root, lyrics, artwork)
            transfer.checkCancelled()
        }
    }
}
