package com.cosmotune.listening

import com.cosmotune.music.data.Http
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.innertube.StreamResolver
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

@kotlinx.serialization.Serializable
data class DiagnosticCheck(val name: String, val ok: Boolean, val detail: String, val elapsedMs: Long = 0)
@kotlinx.serialization.Serializable
data class ListeningDiagnostics(val checkedAt: Long, val checks: List<DiagnosticCheck>) {
    /** Deliberately contains no URLs, account identifiers, song IDs, exception messages or headers. */
    fun report(): String = buildString {
        appendLine("Cosmotune connection check")
        appendLine("Checked: ${java.time.Instant.ofEpochMilli(checkedAt)}")
        checks.forEach { appendLine("${it.name}: ${if (it.ok) "OK" else "FAILED"} — ${it.detail} (${it.elapsedMs} ms)") }
    }
}

object ListeningNetwork {
    suspend fun artists(query: String): List<WatchedArtist> = withTimeout(25_000) {
        require(query.trim().length >= 2) { "Enter at least two characters." }
        YtMusicRepository.search(query.trim(), SearchFilter.ARTISTS).getOrThrow()
            .filterIsInstance<SearchResult.Browse>().map { it.item }.filter { it.type == BrowseType.ARTIST }
            .distinctBy { it.browseId }.take(12).map { WatchedArtist(it.browseId, it.title) }
    }

    suspend fun releases(artist: WatchedArtist): List<ReleaseNotice> = withTimeout(25_000) {
        val page = YtMusicRepository.artistPage(artist.id).getOrThrow()
        page.sections.flatMap { it.items }.filter { it.browseId?.startsWith("MPRE") == true }
            .distinctBy { it.browseId }.map { ReleaseNotice(it.browseId!!, artist.id, artist.name, it.title, it.thumbnailUrl) }
    }

    suspend fun diagnose(song: Song?, media: suspend (Song) -> DiagnosticCheck = ::youtubeMedia): ListeningDiagnostics {
        val api = timed("Music catalogue") {
            withTimeout(25_000) { YtMusicRepository.search("music", SearchFilter.SONGS).getOrThrow() }
            "Search request succeeded"
        }
        val stream = if (song == null) DiagnosticCheck("Media stream", false, "Choose a track before running the media check")
            else if (song.localUri != null || song.localPath != null) DiagnosticCheck("Media stream", true, "Local track; no remote media request needed")
            else try { media(song) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { DiagnosticCheck("Media stream", false, failure(e)) }
        return ListeningDiagnostics(System.currentTimeMillis(), listOf(api, stream))
    }

    suspend fun youtubeMedia(song: Song): DiagnosticCheck = timed("YouTube media stream") {
        withTimeout(30_000) {
            val url = StreamResolver.resolve(song.videoId)
            readMedia(url, StreamResolver.mediaHeadersFor(url))
        }
    }

    suspend fun readMedia(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        // Reuse the app's transport (including its proxy) and bound both time and bytes.
        val client = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-1023").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw MediaStatus(response.code)
            val body = response.body
            require(body.byteStream().read(ByteArray(1024)) > 0) { "Empty media" }
            "Media host returned data (HTTP ${response.code})"
        }
    }

    suspend fun timed(name: String, action: suspend () -> String): DiagnosticCheck {
        val started = System.nanoTime()
        return try { DiagnosticCheck(name, true, action(), (System.nanoTime() - started) / 1_000_000) }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { DiagnosticCheck(name, false, "Request timed out", (System.nanoTime() - started) / 1_000_000) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { DiagnosticCheck(name, false, failure(e), (System.nanoTime() - started) / 1_000_000) }
    }

    fun failure(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(10).toList()
        return when {
            causes.any { it is UnknownHostException } -> "DNS lookup failed. Check your connection or network settings."
            causes.any { it is SSLException } -> "TLS connection failed. Check the device clock and network."
            causes.any { it is SocketTimeoutException } -> "Request timed out. Retry when the connection improves."
            causes.any { it is MediaStatus && (it.code == 401 || it.code == 403) } -> "The media host rejected access. Retry or choose another source."
            causes.any { it is MediaStatus && it.code == 429 } -> "The provider is limiting requests. Wait before retrying."
            causes.any { it is MediaStatus } -> "The media host returned an error. Retry or choose another source."
            else -> "The request failed. Check your connection and enabled sources, then retry."
        }
    }

    private class MediaStatus(val code: Int) : java.io.IOException()
}
