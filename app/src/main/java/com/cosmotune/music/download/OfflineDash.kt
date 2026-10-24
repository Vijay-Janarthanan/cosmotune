package com.cosmotune.music.download

import android.content.Context
import android.net.Uri
import com.cosmotune.music.data.downloads.OfflineDashManifest
import com.cosmotune.music.data.downloads.ManifestDownloadTransport
import java.io.File
import java.util.Locale
import kotlin.math.ceil

/**
 * An offline copy of an MPEG-DASH audio manifest, saved as an HLS package.
 *
 * ### Why DASH exists here at all
 *
 * Because the shape of a module's stream is the backend's choice and not ours.
 * The Tidal module served `.m3u8` until September 2026 and `.mpd` afterwards —
 * same track, same requested tier, no version change on our side. Playback
 * survived that by being told the mime type
 * ([PlaybackService][com.cosmotune.music.playback.PlaybackService]'s
 * `withResolvedStreamType`); downloads did not, because
 * [Downloads.routeFor][Downloads] recognised only `.m3u8` as a manifest and
 * treated everything else as a file. A `.mpd` therefore went down the ordinary
 * fetch-and-save path and wrote **2,738 bytes of XML into a file called
 * `Artist - Track.flac`** — a download that reported success, carried no
 * lyrics and no cover, and could never play. [MediaTagger.embed] wraps its
 * taggers in `runCatching`, so even tagging that file failed quietly.
 *
 * ### Why it is written out as HLS
 *
 * The samples are identical. Both manifests describe the same fMP4 segments —
 * an initialisation segment and a run of numbered media segments — and Media3
 * reads those through the same extractor either way. So the only real
 * difference is the index sitting in front of them, and rewriting the index is
 * far less work than teaching the rest of the app a second package format:
 * [DownloadStore.delete] finds a package by looking for a file named
 * `playlist.m3u8`, [Downloads.remember] finds the saved cover by the same
 * test, and playback picks the HLS source off the `.m3u8` extension. Emitting
 * a playlist those three already understand means none of them change, and an
 * offline DASH download is byte-for-byte as playable as an offline HLS one.
 *
 * The sibling of [OfflineHls], deliberately not merged with it. That one
 * rewrites a playlist it was given and is the code path every existing offline
 * download was saved by; this one builds a playlist from a different index
 * format. Sharing a body between them would put the proven path at risk to
 * save about twenty lines.
 *
 * ### What is not supported
 *
 * Encrypted manifests (`ContentProtection`), and multi-period ones. Both are
 * rejected outright rather than half-saved, on the same reasoning as
 * [OfflineHls]: a package that is missing part of its audio is worse than a
 * download that says it could not be made, because only the second is
 * recoverable by the person it happens to.
 */
internal object OfflineDash {

    /** Whether [url] is a manifest this can save, judged the way the app judges every other one — on the extension. */
    fun handles(url: String): Boolean =
        url.substringBefore('?').endsWith(".mpd", ignoreCase = true)

    suspend fun save(
        context: Context,
        id: String,
        url: String,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit,
        lyrics: LyricsTag.Embeddable?,
        artwork: MediaTagger.Artwork?,
    ): Uri = ManifestDownloadTransport.run(url, headers) { transfer ->
        val plan = parse(transfer.read(url, ManifestDownloadTransport.MAX_MANIFEST_BYTES).toString(Charsets.UTF_8))
        val remotes = listOf(plan.initialization) + plan.media
        OfflineMusicPackage.build(context, id) { root ->
            remotes.forEachIndexed { index, remote ->
                transfer.checkCancelled()
                java.io.FileOutputStream(File(root, localName(index))).use { output ->
                    transfer.copy(transfer.resolve(remote), output); output.fd.sync()
                }
                onProgress(index + 1L, remotes.size.toLong())
            }
            OfflineMusicPackage.write(File(root, "playlist.m3u8"), playlist(plan).toByteArray(Charsets.UTF_8))
            OfflineMusicPackage.extras(root, lyrics, artwork)
            transfer.checkCancelled()
        }
    }

    private fun localName(index: Int) = "segment-${index.toString().padStart(5, '0')}.m4s"

    /** One audio rendition's segments, resolved from the template into plain URLs. */
    internal class Plan(
        val initialization: String,
        val media: List<String>,
        val seconds: List<Double>,
    )

    /**
     * The segment list a manifest describes.
     *
     * Read with regexes rather than an XML parser, and narrowly: this is not a
     * general DASH implementation and should not read like one. It handles the
     * single-period, single-audio-representation, `SegmentTemplate` manifests
     * the source modules actually serve, and refuses everything else instead of
     * guessing. Media3 remains the real DASH implementation — this only has to
     * be right about the manifests that reach a download.
     */
    internal fun parse(manifest: String): Plan = OfflineDashManifest.parse(manifest).let { Plan(it.initialization, it.media, it.seconds) }

    /** The playlist Media3 will read back, pointing at the files just written. */
    internal fun playlist(plan: Plan): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#EXT-X-TARGETDURATION:${ceil(plan.seconds.maxOrNull() ?: 0.0).toInt()}")
        appendLine("""#EXT-X-MAP:URI="${localName(0)}"""")
        plan.seconds.forEachIndexed { at, seconds ->
            appendLine("#EXTINF:${"%.3f".format(Locale.ROOT, seconds)},")
            appendLine(localName(at + 1))
        }
        appendLine("#EXT-X-ENDLIST")
    }

}
