package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Downloads resolved audio into the user's own Cosmotune folder. */
object DesktopDownloadManager {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Keeps an interrupted transfer in place and resumes it when the user tries again. */
    suspend fun download(
        song: Song,
        quality: String = DesktopPersistence().string("download_quality", "LOSSLESS"),
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result<Song> {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[song.videoId] = job
        return try {
            require(song.localPath == null) { "This track is already local" }
            val stream = DesktopMusicSources.resolve(song, quality, forDownload = true).getOrThrow()
            com.cosmotune.music.data.sources.ProviderDownloadTransfers.transfer(
                stream.sourceId ?: URI.create(stream.url).host.orEmpty().ifBlank { "catalogue" }) {
            val directory = Path.of(System.getProperty("user.home"), "Music", com.cosmotune.CosmoConfig.APP_NAME)
            Files.createDirectories(directory)
            val safeName = buildString {
                append(song.artist)
                append(" - ")
                append(song.title)
            }.replace(ILLEGAL_FILENAME, "_").trim().take(180).ifBlank { song.videoId }
            // A manifest is an index of the audio, not the audio: fetched as a file it saved a few
            // KB of XML as `.flac`. Its segments are fMP4, so it can only become FLAC or M4A.
            val manifest = DesktopManifestDownload.isManifest(stream)
            val extension = stream.format.fileExtension().let { if (manifest && it != "flac") "m4a" else it }
            // Distinct recordings and requested renditions must not reuse a title-only filename.
            val rendition = downloadIdentity(song.videoId, stream.sourceId, quality, extension)
            val target = directory.resolve("$safeName [$rendition].$extension")
            val temporary = directory.resolve(".$safeName [$rendition].$extension.part")
            val claimedLossless = quality.equals("LOSSLESS", ignoreCase = true) && stream.format.isLossless
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                // A file saved before this check is labelled from its bytes but never deleted here.
                return@transfer Result.success(verified(song, target.toFile(), claimedLossless, deleteIfNotAudio = false))
            }

            if (manifest) {
                // Segments are fetched and joined whole, so there is no partial file to resume.
                Files.deleteIfExists(temporary)
                Files.newOutputStream(temporary).buffered().use { output ->
                    DesktopManifestDownload.save(
                        stream,
                        extension,
                        output,
                        tempDir = java.io.File(System.getProperty("java.io.tmpdir")),
                        userAgent = DOWNLOAD_USER_AGENT,
                    ) { done, total -> onProgress(done, total) }
                }
                check(Files.size(temporary) > 0L) { "Download failed: nothing was sent" }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                return@transfer Result.success(verified(song, target.toFile(), claimedLossless))
            }

            DesktopAudioFileTransfer.save(stream.url, stream.headers, target.toFile(), DOWNLOAD_USER_AGENT, progress = onProgress)
            Result.success(verified(song, target.toFile(), claimedLossless))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        } finally {
            if (job != null) activeJobs.remove(song.videoId, job)
        }
    }

    /**
     * MN-03: the saved song carries what its bytes prove, not what the source claimed. A lossless
     * claim over AAC/MP3 bytes loses its lossless badge and names the codec that arrived; a web page
     * or JSON error saved in place of audio is removed and the download fails.
     */
    internal fun verified(song: Song, file: java.io.File, claimedLossless: Boolean, deleteIfNotAudio: Boolean = true): Song {
        val delivered = com.cosmotune.audio.DeliveredAudio.inspect(file)
        val honest = com.cosmotune.audio.honestQuality(claimedLossless, delivered)
        if (honest.evidence == com.cosmotune.audio.QualityEvidence.NOT_AUDIO) {
            if (deleteIfNotAudio) file.delete()
            error("The source sent a web page instead of audio")
        }
        if (honest.evidence == com.cosmotune.audio.QualityEvidence.LOSSY_DESPITE_CLAIM) {
            DesktopTrackLog.log("download of '${song.title}' was offered as lossless but is ${delivered?.codec}")
        }
        val keptClaim = song.sourceQuality?.takeUnless { it.uppercase() in com.cosmotune.audio.LOSSLESS_LABELS }
        return song.copy(
            localUri = file.toPath().toUri().toString(), localPath = file.toString(),
            downloadFormat = com.cosmotune.audio.verifiedDownloadLabel(song.downloadFormat, delivered, claimedLossless),
            sourceQuality = honest.sourceQuality ?: keptClaim,
            sourceHiRes = honest.hiRes,
        )
    }

    fun cancel(videoId: String) {
        activeJobs[videoId]?.cancel(CancellationException("Download cancelled"))
    }

    /**
     * The file saved for [song], or null when the record has outlived it.
     *
     * A download record is a claim about a folder this app does not own: the listener is expected
     * to manage it with a file manager, so an entry can name a file that has since been moved or
     * deleted. Android checks before it answers for the same reason — without it the player is
     * handed a path that is simply not there and the track refuses to play, saying only "could not
     * open stream".
     */
    fun savedFile(song: Song): Path? {
        val path = song.localPath?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: song.localUri?.takeIf { it.startsWith("file:") }
                ?.let { runCatching { Path.of(URI(it)) }.getOrNull() }
            ?: return null
        return path.takeIf { Files.isRegularFile(it) && runCatching { Files.size(it) > 0 }.getOrDefault(false) }
    }

    /** Whether [song] is really on this computer, rather than merely recorded as being. */
    fun isSaved(song: Song): Boolean = savedFile(song) != null

    /**
     * The records that still name a file, with the rest dropped.
     *
     * A claim that has just been shown to be false is not worth keeping to be shown false again on
     * the next play.
     */
    fun verified(downloads: List<Song>): List<Song> = downloads.filter(::isSaved)

    /** Deletes the file saved for [song]. Returns whether there was one to delete. */
    fun delete(song: Song): Boolean {
        val file = savedFile(song) ?: return false
        return runCatching { Files.deleteIfExists(file) }
            .onFailure { DesktopTrackLog.log("could not delete the download of '${song.title}': ${it.message}") }
            .getOrDefault(false)
    }

    private fun DesktopStreamFormat.fileExtension(): String {
        val codec = codec.orEmpty().substringAfterLast('/').substringBefore(';').lowercase()
        return when (codec) {
            "flac" -> "flac"
            "wav", "wave" -> "wav"
            "alac" -> "m4a"
            "aac" -> "m4a"
            "mp3" -> "mp3"
            "ogg", "opus" -> "ogg"
            "webm" -> "webm"
            else -> "m4a"
        }
    }

    internal fun downloadIdentity(id: String, source: String?, quality: String, extension: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("$id|${source.orEmpty()}|$quality|$extension".toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
    private const val DOWNLOAD_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val ILLEGAL_FILENAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
}
