package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.data.Csv
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MusicPlaylistFile(val name: String, val text: String, val baseDirectory: String? = null) {
    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        fun decode(name: String, bytes: ByteArray, baseDirectory: String? = null): MusicPlaylistFile {
            require(bytes.size <= MAX_BYTES) { "Choose a music playlist smaller than 4 MB." }
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { error("Save this playlist as UTF-8 and import it again.") }
            return MusicPlaylistFile(name, text, baseDirectory)
        }
    }
}

data class MusicPlaylistImportRow(val number: Int, val title: String, val artist: String = "", val album: String? = null,
    val song: Song? = null, val canSearch: Boolean = false, val detail: String = "")

data class MusicPlaylistPreview(val name: String, val rows: List<MusicPlaylistImportRow>) {
    val songs: List<Song> get() = rows.mapNotNull { it.song }
    val pending: Int get() = rows.count { it.canSearch && it.song == null }
    val skipped: Int get() = rows.count { it.song == null && !it.canSearch }
}

/** Parses documents only: no remote document, media URL or imported filesystem path is opened. */
object MusicPlaylistImport {
    private data class Entry(val location: String = "", val title: String = "", val artist: String = "", val album: String? = null,
        val searchable: Boolean = true)
    private const val MAX_ROWS = 10_000
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val windowsDrive = Regex("^[A-Za-z]:[\\\\/].*")

    fun preview(file: MusicPlaylistFile, library: List<Song>, available: (Song) -> Boolean = { true }): MusicPlaylistPreview {
        require(file.text.toByteArray(Charsets.UTF_8).size <= MusicPlaylistFile.MAX_BYTES) { "Choose a music playlist smaller than 4 MB." }
        val text = file.text.removePrefix("\uFEFF")
        require(text.count { it == '\n' } <= 30_000) { "Import at most 10,000 playlist entries." }
        require(!text.lineSequence().any { it.trimStart().startsWith("#EXT-X-", true) }) { "Choose a music playlist, not an HLS stream manifest." }
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val csv = file.name.endsWith(".csv", true) || first.split(',').any { header(it.trim(' ', '"')) in TITLE_HEADERS }
        val entries = if (csv) csvEntries(text) else lineEntries(text)
        require(entries.size <= MAX_ROWS) { "Import at most 10,000 playlist entries." }
        require(entries.isNotEmpty()) { "The playlist contains no tracks." }
        val byPath = HashMap<String, Song>()
        val byUri = HashMap<String, Song>()
        val music = library.filterNot { it.videoId.startsWith("podio:") }
        music.forEach { song ->
            song.localPath?.takeIf { it.isNotBlank() }?.let { raw ->
                lexicalPath(raw, null)?.let { byPath.putIfAbsent(it, song) }
                // Only paths already in the granted library are canonicalised; imported unknown
                // paths (including network shares) never trigger filesystem or SMB access.
                runCatching { File(raw).canonicalPath }.getOrNull()?.let { lexicalPath(it, null) }
                    ?.let { byPath.putIfAbsent(it, song) }
            }
            song.localUri?.let { raw ->
                byUri.putIfAbsent(raw, song)
                filePath(raw)?.let { lexicalPath(it, null) }?.let { byPath.putIfAbsent(it, song) }
            }
        }
        val byId = music.associateBy { it.videoId }
        val rows = entries.mapIndexed { index, entry ->
            fun matched(song: Song, reason: String): MusicPlaylistImportRow {
                val readable = available(song)
                return MusicPlaylistImportRow(index + 1, song.title, song.artist, song.albumName,
                    song.takeIf { readable }, detail = if (readable) reason else "The library file is unavailable")
            }
            val location = entry.location.trim()
            val known = byUri[location] ?: byId[location] ?: (filePath(location) ?: location).let { raw ->
                lexicalPath(raw, file.baseDirectory)?.let(byPath::get)
            }
            if (known != null) matched(known, "Matched existing library")
            else {
                val id = youtubeId(location)
                if (id != null) {
                    val existing = byId[id]
                    matched(existing ?: Song(id, entry.title.ifBlank { "YouTube track $id" }, entry.artist.ifBlank { "Unknown Artist" }, null,
                        albumName = entry.album), "YouTube music link")
                } else if (entry.searchable && entry.title.isNotBlank() && entry.artist.isNotBlank()) {
                    val local = music.filter { key(it.title) == key(entry.title) && TrackMatcher.sharesArtist(entry.artist, it.artist) &&
                        (entry.album.isNullOrBlank() || key(it.albumName.orEmpty()) == key(entry.album)) }
                        .filter(available)
                    if (local.size == 1) matched(local.single(), "Matched library title and artist")
                    else MusicPlaylistImportRow(index + 1, entry.title, entry.artist, entry.album, canSearch = true,
                        detail = if (local.size > 1) "Multiple library matches; review a catalogue match" else "Needs catalogue matching")
                } else MusicPlaylistImportRow(index + 1, entry.title.ifBlank { "Entry ${index + 1}" }, entry.artist, entry.album,
                    detail = if (location.isBlank()) "Missing track title and artist" else "No granted library file or supported music link")
            }
        }
        val named = text.lineSequence().firstOrNull { it.startsWith("#PLAYLIST:", true) }?.substringAfter(':')?.trim()
        return MusicPlaylistPreview(clean(named ?: file.name.substringBeforeLast('.').ifBlank { "Imported playlist" }, 80), rows)
    }

    /** Explicitly invoked from the preview. Duplicate rows reuse a lookup while retaining order. */
    suspend fun match(preview: MusicPlaylistPreview, lookup: suspend (MusicPlaylistImportRow) -> Song?, limit: Int = 100): MusicPlaylistPreview = coroutineScope {
        require(limit in 1..100)
        val wanted = preview.rows.filter { it.song == null && it.canSearch }.distinctBy { listOf(it.title, it.artist, it.album) }.take(limit)
        val gate = Semaphore(3)
        val answers = wanted.map { row -> async {
            gate.withPermit {
                val found = try { lookup(row) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                val accepted = found?.takeIf { TrackMatcher.sharesArtist(row.artist, it.artist) &&
                    TrackMatcher.score(it, TrackMatcher.Target(row.title, row.artist, album = row.album)) != null }
                listOf(row.title, row.artist, row.album) to accepted
            }
        } }.awaitAll().toMap()
        preview.copy(rows = preview.rows.map { row ->
            val identity = listOf(row.title, row.artist, row.album)
            if (row.song != null || !row.canSearch || identity !in answers) row else {
                val song = answers[identity]
                row.copy(song = song, canSearch = false, detail = if (song != null) "Matched music catalogue" else "No recording verified in this lookup")
            }
        })
    }

    private fun csvEntries(text: String): List<Entry> {
        val rows = Csv.parse(text)
        val headings = rows.firstOrNull()?.map(::header).orEmpty()
        fun column(names: Set<String>) = headings.indexOfFirst { it in names }
        val title = column(TITLE_HEADERS); val artist = column(setOf("artist", "artists", "artistname", "artistnames", "singer"))
        val album = column(setOf("album", "albumname")); val location = column(setOf("url", "uri", "link", "path", "location", "file", "trackurl", "id", "trackid"))
        require(title >= 0 || location >= 0) { "CSV needs a title or music-link/path column." }
        return rows.drop(1).filter { row -> row.any(String::isNotBlank) }.map { row ->
            fun value(index: Int, length: Int = 1_000) = clean(row.getOrNull(index).orEmpty(), length)
            Entry(value(location, 16_384), value(title), value(artist), value(album).takeIf(String::isNotBlank))
        }
    }

    private fun lineEntries(text: String): List<Entry> {
        var label = ""
        val result = ArrayList<Entry>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            require(line.length <= 16_384) { "A playlist entry is too long." }
            if (line.startsWith("#EXTINF:", true)) label = line.substringAfter(',', "")
            else if (line.isNotBlank() && !line.startsWith('#')) {
                // EXTINF labels describe the referenced file; a missing file must never cause an
                // automatic title search or a different recording to be substituted.
                result += Entry(location = line, title = clean(label.substringAfter(" - ", label), 1_000),
                    artist = if (label.contains(" - ")) clean(label.substringBefore(" - "), 1_000) else "", searchable = false)
                label = ""
            }
            require(result.size <= MAX_ROWS) { "Import at most 10,000 playlist entries." }
        }
        return result
    }

    private fun youtubeId(value: String): String? = runCatching {
        if (videoId.matches(value)) return value
        val uri = URI(value)
        require(uri.scheme.equals("https", true) && uri.userInfo == null && uri.port in listOf(-1, 443))
        val id = when (uri.host?.lowercase(Locale.ROOT)) {
            "youtu.be", "www.youtu.be" -> uri.path.removePrefix("/").takeIf { !it.contains('/') }
            "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com" -> {
                require(uri.path == "/watch")
                val values = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "v" }
                require(values.size == 1)
                URLDecoder.decode(values.single().substringAfter('=', ""), "UTF-8")
            }
            else -> null
        }
        id?.takeIf { videoId.matches(it) }
    }.getOrNull()

    private fun filePath(value: String): String? = runCatching {
        val uri = URI(value)
        require(uri.scheme.equals("file", true) && uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null)
        File(uri).path
    }.getOrNull()

    private fun lexicalPath(value: String, base: String?): String? = runCatching {
        if (value.isBlank() || (!windowsDrive.matches(value) && Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value))) return null
        val raw = File(value).toPath()
        val path = if (raw.isAbsolute) raw else base?.let { File(it).toPath().resolve(raw) } ?: return null
        if (!path.isAbsolute) return null
        val key = path.normalize().toString()
        if (System.getProperty("os.name").startsWith("Windows", true)) key.lowercase(Locale.ROOT) else key
    }.getOrNull()

    private fun clean(value: String, limit: Int) = value.replace(Regex("[\\p{Cntrl}]"), " ").trim().take(limit)
    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    private fun header(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    private val TITLE_HEADERS = setOf("title", "track", "trackname", "song", "songtitle")
}
