package com.cosmotune.reading.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One file of an audiobook: where it plays from (an http(s) address, a file path or an Android document address) and its length. */
@Serializable data class ReadingAudioTrack(val url: String, val title: String = "", val durationMs: Long = 0)
@Serializable data class ReadingAudioChapter(val title: String, val startMs: Long)
@Serializable enum class ReadingAudioSource(val label: String) { DEVICE("On this device"), AUDIOBOOKSHELF("Audiobookshelf"), LIBRIVOX("LibriVox") }

/**
 * An audiobook on the Listen shelf: its tracks in order, chapters (times across the whole book), and where the listener is
 * ([positionMs], across the whole book too). [remoteId] is the Audiobookshelf item or the Internet Archive identifier.
 */
@Serializable data class ReadingAudiobook(val id: String, val title: String, val author: String = "", val narrator: String = "",
    val description: String = "", val coverPath: String? = null, val coverUrl: String? = null,
    val source: ReadingAudioSource = ReadingAudioSource.DEVICE, val tracks: List<ReadingAudioTrack> = emptyList(),
    val chapters: List<ReadingAudioChapter> = emptyList(), val positionMs: Long = 0, val finished: Boolean = false,
    val remoteId: String? = null, val addedAtMs: Long = System.currentTimeMillis(), val listenedAtMs: Long = 0,
    /** The book's own ebook on the same server, when it has one (Audiobookshelf). */
    val hasEbook: Boolean = false) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** The listener's own Audiobookshelf server: its address, who signed in, and the key it gave (never shown). */
@Serializable data class ReadingAudiobookshelf(val server: String, val username: String, val token: String, val libraryId: String = "")

/** Where a place across the whole book falls: which track, and how far into it. */
internal object ReadingAudioMath {
    fun locate(tracks: List<ReadingAudioTrack>, positionMs: Long): Pair<Int, Long> {
        var left = positionMs.coerceAtLeast(0)
        tracks.forEachIndexed { index, track ->
            if (track.durationMs <= 0 || left < track.durationMs || index == tracks.lastIndex) return index to left.coerceAtMost(track.durationMs.takeIf { it > 0 } ?: left)
            left -= track.durationMs
        }
        return 0 to 0L
    }
    fun overall(tracks: List<ReadingAudioTrack>, index: Int, inTrackMs: Long): Long = tracks.take(index).sumOf { it.durationMs } + inTrackMs.coerceAtLeast(0)
    /** The chapter playing at [positionMs]; with no chapters, the tracks stand in for them. */
    fun chapters(book: ReadingAudiobook): List<ReadingAudioChapter> = book.chapters.ifEmpty {
        var start = 0L
        book.tracks.mapIndexed { index, track -> ReadingAudioChapter(track.title.ifBlank { "Part ${index + 1}" }, start).also { start += track.durationMs } }
    }
    fun chapterAt(book: ReadingAudiobook, positionMs: Long): Int = chapters(book).indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    fun id(vararg parts: String): String = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}

/**
 * Audiobooks for the Listen shelf: from a folder or file on this device (M4B with chapters, or a folder of MP3s), LibriVox's
 * public-domain recordings on the Internet Archive, and the listener's own Audiobookshelf server, whose place goes back to it.
 */
class ReadingAudioLibrary internal constructor(private val store: ReadingStore, private val root: File, client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val covers = File(root, "covers").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    // ---- This device ------------------------------------------------------------------------------------------------------

    /**
     * Adds the audiobook in [files] (one M4B, or the audio files of a folder, played in name order), named [folderName] when the
     * files do not say. Gives the book, or the one already on the shelf from the same place.
     */
    suspend fun addFromDevice(folderName: String, files: List<ReadingFolderFile>): ReadingAudiobook {
        val audio = files.filter { it.name.substringAfterLast('.').lowercase() in ReadingAudioMeta.extensions }
            .sortedWith { a, b -> ReadingConvert.natural(a.name.lowercase(), b.name.lowercase()) }
        require(audio.isNotEmpty()) { "There are no audio files here. Choose an M4B or MP3 audiobook, or a folder of them." }
        require(audio.size <= 2_000) { "This folder has too many audio files for one audiobook." }
        val id = ReadingAudioMath.id("device", audio.first().location)
        store.state.value.audiobooks.firstOrNull { it.id == id }?.let { return it }
        val infos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { audio.map { ReadingAudioMeta.read(it.name, it.size, it.open) } }
        val first = infos.first()
        val tracks = audio.mapIndexed { index, file ->
            ReadingAudioTrack(file.location, infos[index].title.ifBlank { file.name.substringBeforeLast('.') }, infos[index].durationMs)
        }
        // A single M4B's own chapters; for many files, each file is a chapter (see ReadingAudioMath.chapters).
        var offset = 0L
        val chapters = if (audio.size == 1) first.chapters.map { ReadingAudioChapter(it.title.ifBlank { "Chapter" }, it.startMs) }
            else infos.flatMapIndexed { index, info -> info.chapters.map { ReadingAudioChapter(it.title, it.startMs + offset) }.also { offset += tracks[index].durationMs } }
        val cover = infos.firstNotNullOfOrNull { it.cover }?.let { bytes -> File(covers, "$id.img").apply { writeBytes(bytes) }.path }
        val title = (if (audio.size == 1) first.title else first.album.ifBlank { folderName }).ifBlank { folderName.ifBlank { audio.first().name.substringBeforeLast('.') } }
        val book = ReadingAudiobook(id, title, first.author, coverPath = cover, tracks = tracks, chapters = chapters)
        store.update { it.copy(audiobooks = it.audiobooks + book) }
        return book
    }

    // ---- LibriVox (Internet Archive) --------------------------------------------------------------------------------------

    /** LibriVox recordings matching [query] (the most listened first when it is blank). */
    suspend fun librivox(query: String, page: Int = 0): List<ReadingAudiobook> {
        val term = query.take(200).replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ").trim()
        val filter = "collection:librivoxaudio AND mediatype:audio" + if (term.isBlank()) "" else """ AND (title:"$term" OR creator:"$term")"""
        val url = ARCHIVE.newBuilder().addPathSegment("advancedsearch.php").addQueryParameter("q", filter).addQueryParameter("rows", "30")
            .addQueryParameter("page", (page + 1).toString()).addQueryParameter("sort[]", "downloads desc").addQueryParameter("output", "json")
            .apply { listOf("identifier", "title", "creator", "description").forEach { addQueryParameter("fl[]", it) } }.build()
        val docs = (get(url).jsonObject["response"]?.jsonObject?.get("docs") as? JsonArray).orEmpty()
        return docs.mapNotNull { it as? JsonObject }.mapNotNull { row ->
            val identifier = row.text("identifier").takeIf { ARCHIVE_ID.matches(it) } ?: return@mapNotNull null
            ReadingAudiobook("librivox:$identifier", row.text("title").ifBlank { return@mapNotNull null }, row.texts("creator").joinToString(", "),
                description = row.text("description").replace(Regex("<[^>]+>"), " ").replace(Regex("""\s+"""), " ").trim().take(600),
                coverUrl = "https://archive.org/services/img/$identifier", source = ReadingAudioSource.LIBRIVOX, remoteId = identifier)
        }
    }

    /** [found] with its chapters (the recording's 64 kbit/s MP3 files), put on the shelf. */
    suspend fun addLibrivox(found: ReadingAudiobook): ReadingAudiobook {
        store.state.value.audiobooks.firstOrNull { it.id == found.id }?.let { return it }
        val identifier = requireNotNull(found.remoteId?.takeIf { ARCHIVE_ID.matches(it) })
        val metadata = get(ARCHIVE.newBuilder().addPathSegment("metadata").addPathSegment(identifier).build()).jsonObject
        val tracks = librivoxTracks(identifier, metadata)
        require(tracks.isNotEmpty()) { "This recording has no chapters to play." }
        val book = found.copy(tracks = tracks, addedAtMs = System.currentTimeMillis())
        store.update { it.copy(audiobooks = it.audiobooks + book) }
        return book
    }

    // ---- Audiobookshelf ---------------------------------------------------------------------------------------------------

    /** Signs in to the listener's own Audiobookshelf at [server]; an API key can stand in for the password (leave [username] blank). */
    suspend fun connectAudiobookshelf(server: String, username: String, password: String) {
        val base = audiobookshelfBase(server)
        val token = if (username.isBlank()) password.trim().also { require(it.isNotEmpty()) { "Enter your password or an API key." } } else {
            val body = buildJsonObject { put("username", username.trim()); put("password", password) }.toString().toRequestBody(JSON)
            val answer = call(Request.Builder().url(base.newBuilder().addPathSegment("login").build()).post(body).build(), "Audiobookshelf did not accept that name and password.")
            val user = answer.jsonObject["user"]?.jsonObject ?: error("Audiobookshelf gave an answer Read does not understand.")
            user.text("accessToken").ifBlank { user.text("token") }.ifBlank { error("Audiobookshelf did not give a sign-in key.") }
        }
        val account = ReadingAudiobookshelf(base.toString().trimEnd('/'), username.trim(), token)
        val libraries = libraries(account)
        require(libraries.isNotEmpty()) { "This Audiobookshelf has no audiobook library you can see." }
        store.update { it.copy(audiobookshelf = account.copy(libraryId = libraries.first().first)) }
    }

    suspend fun forgetAudiobookshelf() = store.update { it.copy(audiobookshelf = null) }

    /** The server's book libraries as (id, name). */
    suspend fun libraries(account: ReadingAudiobookshelf = requireAccount()): List<Pair<String, String>> =
        (call(request(account, "api", "libraries")).jsonObject["libraries"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.text("mediaType").ifBlank { "book" } == "book" }.map { it.text("id") to it.text("name") }

    suspend fun chooseLibrary(id: String) = store.update { old -> old.copy(audiobookshelf = old.audiobookshelf?.copy(libraryId = id)) }

    /** A page of the chosen library's audiobooks (title order), or the ones matching [query]. */
    suspend fun audiobookshelf(query: String = "", page: Int = 0): List<ReadingAudiobook> {
        val account = requireAccount()
        val library = account.libraryId.ifBlank { libraries(account).firstOrNull()?.first ?: return emptyList() }
        val items = if (query.isBlank()) {
            val url = base(account).newBuilder().addPathSegments("api/libraries").addPathSegment(library).addPathSegment("items")
                .addQueryParameter("limit", "40").addQueryParameter("page", page.toString()).addQueryParameter("sort", "media.metadata.title")
                .addQueryParameter("minified", "1").build()
            (call(authorised(account, url)).jsonObject["results"] as? JsonArray).orEmpty()
        } else {
            val url = base(account).newBuilder().addPathSegments("api/libraries").addPathSegment(library).addPathSegment("search")
                .addQueryParameter("q", query.take(200)).addQueryParameter("limit", "40").build()
            (call(authorised(account, url)).jsonObject["book"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("libraryItem") }
        }
        return items.mapNotNull { it as? JsonObject }.mapNotNull { audiobookshelfBook(account, it) }
    }

    /** [found] with its tracks and chapters, and the place the server has, put on the shelf (or refreshed there). */
    suspend fun addAudiobookshelf(found: ReadingAudiobook): ReadingAudiobook {
        val account = requireAccount()
        val itemId = requireNotNull(found.remoteId)
        val item = call(authorised(account, base(account).newBuilder().addPathSegments("api/items").addPathSegment(itemId).addQueryParameter("expanded", "1").build())).jsonObject
        val full = audiobookshelfBook(account, item, expanded = true) ?: error("This item has no audio to play.")
        require(full.tracks.isNotEmpty()) { "This item has no audio to play." }
        val progress = runCatching { call(request(account, "api", "me", "progress", itemId)).jsonObject }.getOrNull()
        val position = progress?.get("currentTime")?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() }
        val finished = progress?.get("isFinished")?.jsonPrimitive?.booleanOrNull == true
        val existing = store.state.value.audiobooks.firstOrNull { it.id == full.id }
        val book = full.copy(positionMs = maxOf(position ?: 0, existing?.positionMs ?: 0), finished = finished || existing?.finished == true,
            addedAtMs = existing?.addedAtMs ?: System.currentTimeMillis(), listenedAtMs = existing?.listenedAtMs ?: 0)
        store.update { old -> old.copy(audiobooks = old.audiobooks.filterNot { it.id == book.id } + book) }
        return book
    }

    /** The book's ebook on the server, for Read's library (EPUB or PDF), as a name and its bytes. */
    suspend fun audiobookshelfEbook(book: ReadingAudiobook): Pair<String, ByteArray> {
        val account = requireAccount()
        val itemId = requireNotNull(book.remoteId)
        val item = call(authorised(account, base(account).newBuilder().addPathSegments("api/items").addPathSegment(itemId).build())).jsonObject
        val file = item["media"]?.jsonObject?.get("ebookFile")?.jsonObject ?: error("This audiobook has no ebook on the server.")
        val name = file["metadata"]?.jsonObject?.text("filename")?.ifBlank { null } ?: "${book.title}.${file.text("ebookFormat").ifBlank { "epub" }}"
        val request = authorised(account, base(account).newBuilder().addPathSegments("api/items").addPathSegment(itemId).addPathSegment("ebook").build())
        val bytes = http.newCall(request).awaitReading { response ->
            check(response.isSuccessful) { "The ebook could not be fetched (HTTP ${response.code})." }
            require(response.body.contentLength() <= 512L * 1024 * 1024) { "This ebook is too large." }
            response.body.bytes()
        }
        return name to bytes
    }

    // ---- The shelf ----------------------------------------------------------------------------------------------------------

    /** Keeps where [bookId] is (across the whole book); finished when it is within half a minute of the end. Audiobookshelf hears it too. */
    suspend fun savePosition(bookId: String, positionMs: Long, sendRemote: Boolean = false) {
        var saved: ReadingAudiobook? = null
        store.update { old -> old.copy(audiobooks = old.audiobooks.map { book ->
            if (book.id != bookId) book else {
                val clamped = positionMs.coerceIn(0, book.durationMs.takeIf { it > 0 } ?: positionMs.coerceAtLeast(0))
                book.copy(positionMs = clamped, listenedAtMs = System.currentTimeMillis(),
                    finished = book.finished || (book.durationMs > 60_000 && book.durationMs - clamped < 30_000)).also { saved = it }
            }
        }) }
        val book = saved ?: return
        if (sendRemote && book.source == ReadingAudioSource.AUDIOBOOKSHELF) runCatching { pushProgress(book) }
    }

    suspend fun markFinished(bookId: String, finished: Boolean) = store.update { old ->
        old.copy(audiobooks = old.audiobooks.map { if (it.id == bookId) it.copy(finished = finished, positionMs = if (finished) it.positionMs else 0) else it })
    }

    suspend fun remove(bookId: String) {
        store.update { old -> old.copy(audiobooks = old.audiobooks.filterNot { it.id == bookId }) }
        File(covers, "$bookId.img").delete()
    }

    private suspend fun pushProgress(book: ReadingAudiobook) {
        val account = store.state.value.audiobookshelf ?: return
        val body = buildJsonObject {
            put("currentTime", book.positionMs / 1000.0); put("duration", book.durationMs / 1000.0)
            put("progress", book.fraction.toDouble()); put("isFinished", book.finished)
        }.toString().toRequestBody(JSON)
        val url = base(account).newBuilder().addPathSegments("api/me/progress").addPathSegment(requireNotNull(book.remoteId)).build()
        call(Request.Builder().url(url).header("Authorization", "Bearer ${account.token}").patch(body).build())
    }

    // ---- Parsing (kept apart for the tests) -------------------------------------------------------------------------------

    internal fun audiobookshelfBook(account: ReadingAudiobookshelf, item: JsonObject, expanded: Boolean = false): ReadingAudiobook? =
        parseAudiobookshelf(account, item, expanded)

    private fun requireAccount() = store.state.value.audiobookshelf ?: error("Connect your Audiobookshelf first.")
    private fun base(account: ReadingAudiobookshelf) = requireNotNull(account.server.toHttpUrlOrNull()) { "The Audiobookshelf address is not valid." }
    private fun authorised(account: ReadingAudiobookshelf, url: HttpUrl) = Request.Builder().url(url).header("Authorization", "Bearer ${account.token}").build()
    private fun request(account: ReadingAudiobookshelf, vararg path: String) =
        authorised(account, base(account).newBuilder().apply { path.forEach { addPathSegment(it) } }.build())

    private suspend fun get(url: HttpUrl): JsonElement = call(Request.Builder().url(url).header("Accept", "application/json").build())
    private suspend fun call(request: Request, refused: String? = null): JsonElement = http.newCall(request).awaitReading { response ->
        if (refused != null && response.code in setOf(401, 403)) error(refused)
        check(response.code != 401 && response.code != 403) { "The server refused Read. Sign in again in Listen › Audiobookshelf." }
        check(response.isSuccessful) { "The server answered HTTP ${response.code}." }
        json.parseToJsonElement(response.catalogueText(8 * 1024 * 1024))
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val ARCHIVE = "https://archive.org/".toHttpUrlOrNull()!!
        private val ARCHIVE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

        /** The server's address as typed ("books.example.org", with or without https://); plain http only for addresses at home. */
        internal fun audiobookshelfBase(server: String): HttpUrl {
            val typed = server.trim().trimEnd('/')
            val url = (if ("://" in typed) typed else "https://$typed").toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter the server's address, such as https://books.example.org.")
            require(url.isHttps || isHomeNetwork(url.host)) { "Use https:// for a server outside your home network." }
            return url.newBuilder().query(null).fragment(null).build()
        }

        private fun isHomeNetwork(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host.startsWith("10.") ||
            host.startsWith("192.168.") || host.endsWith(".local") || Regex("""172\.(1[6-9]|2[0-9]|3[01])\..*""").matches(host)

        internal fun parseAudiobookshelf(account: ReadingAudiobookshelf, item: JsonObject, expanded: Boolean): ReadingAudiobook? {
            val id = item.text("id").ifBlank { return null }
            val media = item["media"]?.jsonObject ?: return null
            val meta = media["metadata"]?.jsonObject ?: JsonObject(emptyMap())
            val base = account.server.trimEnd('/')
            fun withToken(path: String) = "$base$path" + (if ('?' in path) "&" else "?") + "token=" + java.net.URLEncoder.encode(account.token, "UTF-8")
            val tracks = if (!expanded) emptyList() else (media["tracks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: 0 }.mapNotNull { track ->
                    val content = track.text("contentUrl").takeIf { it.startsWith("/") } ?: return@mapNotNull null
                    ReadingAudioTrack(withToken(content), track.text("title"), ((track["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong())
                }
            val chapters = (media["chapters"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { chapter ->
                val start = chapter["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                ReadingAudioChapter(chapter.text("title").ifBlank { "Chapter" }, (start * 1000).toLong())
            }
            val author = meta.text("authorName").ifBlank { (meta["authors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }.joinToString(", ") }
            return ReadingAudiobook("abs:" + ReadingAudioMath.id(base, id), meta.text("title").ifBlank { "Untitled" }, author,
                narrator = meta.text("narratorName"), description = meta.text("description").replace(Regex("<[^>]+>"), " ").replace(Regex("""\s+"""), " ").trim().take(800),
                coverUrl = withToken("/api/items/$id/cover"), source = ReadingAudioSource.AUDIOBOOKSHELF, tracks = tracks, chapters = chapters, remoteId = id,
                hasEbook = media["ebookFile"].let { it != null && it !is JsonNull } || (media["ebookFormat"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
        }

        internal fun librivoxTracks(identifier: String, metadata: JsonObject): List<ReadingAudioTrack> {
            val files = (metadata["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val mp3 = files.filter { it.text("format") == "64Kbps MP3" }.ifEmpty { files.filter { it.text("format").endsWith("MP3") } }
                .filter { val name = it.text("name"); name.isNotBlank() && '/' !in name && name.endsWith(".mp3", true) }
            return mp3.sortedWith { a, b -> ReadingConvert.natural(a.text("name").lowercase(), b.text("name").lowercase()) }.map { file ->
                val name = file.text("name")
                ReadingAudioTrack(ARCHIVE.newBuilder().addPathSegment("download").addPathSegment(identifier).addPathSegment(name).build().toString(),
                    file.text("title").ifBlank { name.substringBeforeLast('.') }, length(file.text("length")))
            }
        }

        /** "1234.56" seconds or "mm:ss" / "hh:mm:ss", in milliseconds. */
        internal fun length(value: String): Long {
            if (':' in value) return value.split(':').fold(0.0) { total, part -> total * 60 + (part.toDoubleOrNull() ?: 0.0) }.times(1000).toLong()
            return ((value.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        }

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.texts(key: String) = when (val field = this[key]) {
            is JsonArray -> field.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(field.contentOrNull)
            else -> emptyList()
        }
    }
}
