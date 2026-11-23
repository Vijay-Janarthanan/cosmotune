package com.cosmotune.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.cosmotune.ui.collectAsUiState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Plays [book] from where the listener was (or from [fromMs]) on the app's player. */
internal fun ReadingAudioHost.playBook(book: ReadingAudiobook, fromMs: Long = if (book.finished) 0 else book.positionMs) {
    val (index, inTrack) = ReadingAudioMath.locate(book.tracks, fromMs)
    val artwork = book.coverPath?.let { File(it).toURI().toString() } ?: book.coverUrl
    play(book.tracks.mapIndexed { i, track -> ReadingAudioItem(ReadingAudioHost.itemId(book.id, i), track.title.ifBlank { book.title }, book.author, track.playable, artwork) },
        index, inTrack)
}

/** Plays [book] once its stream addresses carry the server's current key (a short-lived Audiobookshelf key may have been renewed). */
internal fun ReadScope.listen(host: ReadingAudioHost, book: ReadingAudiobook, fromMs: Long? = null) = action {
    val fresh = repository.audio.ready(book)
    if (fromMs == null) host.playBook(fresh) else host.playBook(fresh, fromMs)
}

/** Where the player is in [book] across the whole book, or null when it is playing something else. */
internal fun ReadingAudioHost.positionIn(book: ReadingAudiobook): Long? {
    val (id, index) = ReadingAudioHost.parse(nowId) ?: return null
    return if (id != book.id) null else ReadingAudioMath.overall(book.tracks, index, positionMs)
}

/**
 * Keeps each audiobook's place while the app's player has it: every few seconds into the shelf, and every minute (and on pausing)
 * back to Audiobookshelf for its books. A track that finishes moves the place on by itself, as the player goes to the next one.
 */
@Composable
internal fun ReadingAudioTracker(repository: ReadingRepository) {
    val host = LocalReadingAudio.current ?: return
    val state by repository.state.collectAsUiState()
    val playing = ReadingAudioHost.parse(host.nowId)
    val book = playing?.let { (id, _) -> state.audiobooks.firstOrNull { it.id == id } }
    LaunchedEffect(book?.id, host.playing) {
        val current = book ?: return@LaunchedEffect
        var sent = System.currentTimeMillis()
        if (!host.playing) { host.positionIn(current)?.let { runCatching { repository.audio.savePosition(current.id, it, sendRemote = true) } }; return@LaunchedEffect }
        while (true) {
            delay(5_000)
            val at = host.positionIn(current) ?: break
            val remote = System.currentTimeMillis() - sent > 60_000
            try { repository.audio.savePosition(current.id, at, sendRemote = remote) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            if (remote) sent = System.currentTimeMillis()
        }
    }
}

/** "1.2 GB" or "340 MB", for what a download takes on the device. */
internal fun sizeLabel(bytes: Long): String = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1_073_741_824.0) else "${(bytes / 1_048_576).coerceAtLeast(1)} MB"

/** "4 h 12 min" or "35 min" or "50 s". */
internal fun listenTime(ms: Long): String {
    val minutes = ms / 60_000
    return when { ms < 60_000 -> "${ms / 1000} s"; minutes < 60 -> "$minutes min"; else -> "${minutes / 60} h ${minutes % 60} min" }
}

/** "12:05" or "1:02:09", for the place in a track or a chapter. */
internal fun listenClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Listen: audiobooks, played on the app's own player. The book playing now as the colour card, the shelf (books on this device,
 * from LibriVox and from the listener's Audiobookshelf), then the listener's Audiobookshelf library and LibriVox's catalogue.
 */
@Composable
internal fun BookListen(scope: ReadScope) {
    val state by scope.repository.state.collectAsUiState()
    val host = LocalReadingAudio.current
    val folders = rememberReadingFolders()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var librivox by remember { mutableStateOf<List<ReadingAudiobook>?>(null) }
    var shelf by remember { mutableStateOf<List<ReadingAudiobook>?>(null) }
    var looking by remember { mutableStateOf(false) }
    val account = state.audiobookshelf
    LaunchedEffect(searched) {
        looking = true
        librivox = try { scope.repository.audio.librivox(searched) } catch (e: CancellationException) { throw e } catch (e: Exception) { scope.onError(e.message); emptyList() }
        looking = false
    }
    LaunchedEffect(account?.server, account?.libraryId, searched) {
        shelf = null
        if (account != null) shelf = try { scope.repository.audio.audiobookshelf(searched) } catch (e: CancellationException) { throw e } catch (e: Exception) { scope.onError(e.message); emptyList() }
    }
    val chosen = open?.let { id -> state.audiobooks.firstOrNull { it.id == id } }
    if (chosen != null) {
        MangaBackHandler(true) { open = null }
        AudiobookPage(scope, chosen, onBack = { open = null })
        return
    }
    if (connecting) AudiobookshelfSheet(scope, onDismiss = { connecting = false })
    fun addAndOpen(found: ReadingAudiobook) = scope.action {
        val book = if (found.source == ReadingAudioSource.LIBRIVOX) scope.repository.audio.addLibrivox(found) else scope.repository.audio.addAudiobookshelf(found)
        open = book.id
    }
    fun addFolder() = folders.pick { folder -> scope.action {
        val files = folders.list(folder)
        val books = files.filter { it.name.lowercase().endsWith(".m4b") }
        // A folder of M4B files holds a book in each; otherwise its audio files are the parts of one book.
        val added = if (books.size > 1) books.map { scope.repository.audio.addFromDevice(it.name.substringBeforeLast('.'), listOf(it)) }
            else listOf(scope.repository.audio.addFromDevice(folder.name, files))
        if (added.size == 1) open = added.first().id
    } }
    val shelfBooks = state.audiobooks.sortedByDescending { maxOf(it.listenedAtMs, it.addedAtMs) }
    val now = shelfBooks.firstOrNull { host?.positionIn(it) != null } ?: shelfBooks.firstOrNull { it.listenedAtMs > 0 && !it.finished }
    LazyColumn(Modifier.fillMaxSize().testTag("books:listen"), contentPadding = PaddingValues(bottom = scope.bottom)) {
        item(key = "head") {
            ReadPageHead("Listen", if (shelfBooks.size == 1) "1 audiobook" else "${shelfBooks.size} audiobooks") {
                Box {
                    var adding by remember { mutableStateOf(false) }
                    ReadIconButton(CosmoIcons.Plus, "Add audiobooks", { adding = true }, Modifier.testTag("books:listen:add"))
                    OrbitMenu(adding, { adding = false }, listOf(
                        OrbitMenuItem("Add an audiobook folder", detail = "An M4B book, or a folder of MP3 parts", icon = CosmoIcons.Library) { addFolder() },
                        OrbitMenuItem(if (account == null) "Connect Audiobookshelf" else "Audiobookshelf", detail = account?.server ?: "Your own audiobook server",
                            icon = Icons.Rounded.Headphones) { connecting = true },
                    ), orbitFonts(), orbitLook(), header = "Add audiobooks")
                }
            }
        }
        if (host == null) item(key = "no-player") {
            ReadInlineBanner("Audiobooks play in the Cosmotune app on a phone or a computer.", Modifier.padding(top = 4.dp))
        }
        if (now != null) item(key = "now:${now.id}") {
            val playingNow = host?.positionIn(now) != null && host.playing
            OrbitCoverTitleCard(cover = now.coverPath?.let(::File) ?: now.coverUrl, colourUrl = null, key = now.id,
                label = "Audiobook · ${(now.fraction * 100).toInt()} %", title = now.title, line = now.author.ifBlank { null },
                onContinue = host?.let { h -> { if (playingNow) h.toggle() else scope.listen(h, now) } }, onClick = { open = now.id },
                modifier = Modifier.padding(horizontal = ReadMargin).padding(top = 4.dp).testTag("books:listen:now"))
        }
        if (shelfBooks.isEmpty()) item(key = "empty") {
            ReadEmpty("Listen to a book", "Add an audiobook folder from this device, connect your Audiobookshelf, or pick a free LibriVox recording below.")
        } else {
            item(key = "shelf") { ReadSection("Your audiobooks", subtitle = shelfBooks.size.toString()) }
            items(shelfBooks, key = { "book:${it.id}" }) { book ->
                ReadCoverRow(book.title, listOfNotNull(book.author.ifBlank { null }, if (book.finished) "Finished" else if (book.positionMs > 0) "${(book.fraction * 100).toInt()} %" else listenTime(book.durationMs))
                    .joinToString(" · "), book.coverPath?.let(::File) ?: book.coverUrl, onClick = { open = book.id }, key = book.id,
                    highlighted = host?.positionIn(book) != null, modifier = Modifier.padding(horizontal = ReadMargin).testTag("books:listen:book:${book.id}"))
            }
        }
        item(key = "search") {
            ReadSection("Find audiobooks")
            ReadSearchField(query, { query = it }, "Title or author", Modifier.testTag("books:listen:search"), onSearch = { searched = query.trim() })
        }
        if (account != null) {
            item(key = "abs") { ReadSection("Your Audiobookshelf", subtitle = account.server.substringAfter("://")) }
            val found = shelf
            if (found == null) item(key = "abs:wait") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            else if (found.isEmpty()) item(key = "abs:none") { ReadEmpty("Nothing found", "No audiobooks in this library match.") }
            else items(found, key = { "abs:${it.id}" }) { book ->
                ReadCoverRow(book.title, book.author, book.coverUrl, onClick = { addAndOpen(book) }, key = book.id,
                    modifier = Modifier.padding(horizontal = ReadMargin).testTag("books:listen:abs:${book.remoteId}"))
            }
        }
        item(key = "librivox") { ReadSection("LibriVox", subtitle = "Free public-domain recordings") }
        val free = librivox
        if (free == null || looking) item(key = "librivox:wait") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
        else if (free.isEmpty()) item(key = "librivox:none") { ReadEmpty("Nothing found", "Try another title or author.") }
        else items(free, key = { "lv:${it.id}" }) { book ->
            ReadCoverRow(book.title, book.author, book.coverUrl, onClick = { addAndOpen(book) }, key = book.id,
                modifier = Modifier.padding(horizontal = ReadMargin).testTag("books:listen:librivox:${book.remoteId}"))
        }
    }
}

/** An audiobook's page: its cover and details, play and the place, skips, speed, the chapters, and its actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudiobookPage(scope: ReadScope, book: ReadingAudiobook, onBack: () -> Unit) {
    val host = LocalReadingAudio.current
    val look = orbitLook()
    val fonts = orbitFonts()
    val here = host?.positionIn(book)
    val position = here ?: book.positionMs
    val active = here != null
    val chapters = remember(book) { ReadingAudioMath.chapters(book) }
    val current = ReadingAudioMath.chapterAt(book, position)
    val downloading by scope.repository.audio.downloading.collectAsUiState()
    var removingDownload by remember { mutableStateOf(false) }
    if (removingDownload) com.cosmotune.ui.kit.KitSheet(onDismiss = { removingDownload = false }) {
        ReadSheetHeader("Remove the download?", onDone = { removingDownload = false }, doneLabel = "Keep")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Text("The book stays on your shelf and plays from your Audiobookshelf again; your place is kept.",
                style = ReadType.body(), color = look.dim)
            ReadButton("Remove download", { removingDownload = false; scope.action { scope.repository.audio.removeDownload(book.id) } },
                Modifier.testTag("books:audiobook:download:confirm"), kind = ReadKind.TONAL)
        }
        Spacer(Modifier.height(16.dp))
    }
    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, book.durationMs.coerceAtLeast(0))
        if (host == null) return
        val (index, inTrack) = ReadingAudioMath.locate(book.tracks, target)
        val playingTrack = ReadingAudioHost.parse(host.nowId)?.second
        if (active && playingTrack == index) host.seek(inTrack) else scope.listen(host, book, target)
        scope.action { scope.repository.audio.savePosition(book.id, target) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("books:audiobook"), contentPadding = PaddingValues(bottom = scope.bottom)) {
        item(key = "head") {
            ReadPageHead(book.title, book.source.label) { ReadIconButton(CosmoIcons.Close, "Back to Listen", onBack, Modifier.testTag("books:audiobook:back")) }
        }
        item(key = "top") {
            Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin + 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WatchPicture(book.coverPath?.let(::File) ?: book.coverUrl, book.id, Modifier.width(112.dp).aspectRatio(1f), radius = 12.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (book.author.isNotBlank()) androidx.compose.material3.Text(book.author, style = ReadType.rowTitle(), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.narrator.isNotBlank()) androidx.compose.material3.Text("Read by ${book.narrator}", style = ReadType.body(), color = look.dim, maxLines = 2)
                    androidx.compose.material3.Text(if (book.durationMs > 0) "${listenTime(book.durationMs)} · ${listenTime((book.durationMs - position).coerceAtLeast(0))} left" else "${book.tracks.size} parts",
                        style = fonts.label, color = look.dim)
                }
            }
        }
        item(key = "controls") {
            Column(Modifier.fillMaxWidth().padding(horizontal = ReadMargin + 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (book.durationMs > 0) OrbitProgressLine({ (position.toFloat() / book.durationMs).coerceIn(0f, 1f) }, look.ink, Modifier.testTag("books:audiobook:progress"),
                    onSeek = if (host != null) ({ f -> seekTo((f * book.durationMs).toLong()) }) else null)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Text(chapters.getOrNull(current)?.title.orEmpty(), style = fonts.label, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    androidx.compose.material3.Text(listenClock(position), style = fonts.label, color = look.dim)
                }
                if (host == null) androidx.compose.material3.Text("Audiobooks play in the Cosmotune app on a phone or a computer.", style = ReadType.body(), color = look.dim)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadIconButton(Icons.Rounded.Replay30, "Back 30 seconds", { seekTo(position - 30_000) }, Modifier.testTag("books:audiobook:back30"))
                    ReadButton(if (active && host.playing) "Pause" else if (book.positionMs > 0 && !book.finished) "Resume" else "Play",
                        { if (active) host.toggle() else scope.listen(host, book) }, Modifier.testTag("books:audiobook:play"),
                        icon = if (active && host.playing) CosmoIcons.Pause else CosmoIcons.Play)
                    ReadIconButton(Icons.Rounded.Forward30, "Ahead 30 seconds", { seekTo(position + 30_000) }, Modifier.testTag("books:audiobook:ahead30"))
                }
                if (host != null) ReadChipRow(inset = 0.dp) {
                    listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { rate ->
                        ReadChip("${if (rate % 1f == 0f) rate.toInt().toString() else rate.toString()}×", active && kotlin.math.abs(host.rate - rate) < 0.01f, { host.speed(rate) },
                            Modifier.testTag("books:audiobook:speed:$rate"))
                    }
                }
                ReadChipRow(inset = 0.dp) {
                    ReadChip(if (book.finished) "Finished" else "Mark finished", book.finished, { scope.action { scope.repository.audio.markFinished(book.id, !book.finished) } }, icon = CosmoIcons.Check)
                    if (book.source == ReadingAudioSource.AUDIOBOOKSHELF) {
                        val share = downloading[book.id]
                        when {
                            share != null -> ReadChip("Downloading ${(share * 100).toInt()} % · Stop", true, { scope.repository.audio.cancelDownload(book.id) },
                                Modifier.testTag("books:audiobook:download:stop"), icon = CosmoIcons.Close)
                            book.downloaded -> ReadChip("On this device · ${sizeLabel(remember(book.tracks) { scope.repository.audio.downloadedBytes(book.id) })}", true,
                                { removingDownload = true }, Modifier.testTag("books:audiobook:download:remove"), icon = CosmoIcons.Check)
                            else -> ReadChip(if (book.tracks.any { it.localPath != null }) "Finish download" else "Download to listen offline", false,
                                { scope.repository.audio.startDownload(book.id, scope.onError) }, Modifier.testTag("books:audiobook:download"), icon = Icons.Rounded.Download)
                        }
                    }
                    if (book.hasEbook) ReadChip("Read the book", false, { scope.action {
                        val (name, bytes) = scope.repository.audio.audiobookshelfEbook(book)
                        scope.open(scope.repository.importFile(name) { bytes.inputStream() })
                    } }, Modifier.testTag("books:audiobook:ebook"), icon = CosmoIcons.Library)
                    ReadChip("Remove", false, { scope.action { scope.repository.audio.remove(book.id); onBack() } }, Modifier.testTag("books:audiobook:remove"), icon = CosmoIcons.Close)
                }
                if (book.description.isNotBlank()) androidx.compose.material3.Text(book.description, style = ReadType.body(), color = look.dim, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
        item(key = "chapters") { ReadSection("Chapters", subtitle = chapters.size.toString()) }
        itemsIndexed(chapters, key = { index, _ -> "chapter:$index" }) { index, chapter ->
            val end = chapters.getOrNull(index + 1)?.startMs ?: book.durationMs
            ReadSheetRow(chapter.title, if (index == current) CosmoIcons.Check else null, { seekTo(chapter.startMs) },
                Modifier.padding(horizontal = 4.dp).testTag("books:audiobook:chapter:$index"), enabled = host != null,
                trailing = { androidx.compose.material3.Text(listenTime((end - chapter.startMs).coerceAtLeast(0)), style = fonts.label, color = look.dim) })
        }
    }
}

/** Signing in to the listener's own Audiobookshelf (or leaving it), with the library to use when it has several. */
@Composable
private fun AudiobookshelfSheet(scope: ReadScope, onDismiss: () -> Unit) {
    val state by scope.repository.state.collectAsUiState()
    val account = state.audiobookshelf
    var server by remember { mutableStateOf(account?.server.orEmpty()) }
    var username by remember { mutableStateOf(account?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var withKey by remember { mutableStateOf(account?.apiKey == true) }
    var libraries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(account?.server) { libraries = if (account == null) emptyList() else runCatching { scope.repository.audio.libraries() }.getOrDefault(emptyList()) }
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Audiobookshelf", onDone = onDismiss, doneLabel = "Close")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account != null) {
                androidx.compose.material3.Text("Connected to ${account.server}" + (if (account.username.isNotBlank()) " as ${account.username}" else "") +
                    if (account.apiKey) " with an API key." else ".", style = ReadType.body(), color = orbitLook().ink)
                if (libraries.size > 1) ReadChipRow(inset = 0.dp) {
                    libraries.forEach { (id, name) -> ReadChip(name, id == account.libraryId, { scope.action { scope.repository.audio.chooseLibrary(id) } }) }
                }
                Spacer(Modifier.height(4.dp))
                ReadButton("Disconnect", { scope.action { scope.repository.audio.forgetAudiobookshelf() } }, kind = ReadKind.TONAL, modifier = Modifier.testTag("books:abs:disconnect"))
            } else {
                androidx.compose.material3.Text("Your own Audiobookshelf server: Read lists its audiobooks, plays them and sends your place back.",
                    style = ReadType.body(), color = orbitLook().dim)
                OrbitInputField(server, { server = it.take(300) }, "Server address, such as https://books.example.org", modifier = Modifier.fillMaxWidth().testTag("books:abs:server"))
                ReadChipRow(inset = 0.dp) {
                    ReadChip("Password", !withKey, { withKey = false }, Modifier.testTag("books:abs:with-password"))
                    ReadChip("API key", withKey, { withKey = true }, Modifier.testTag("books:abs:with-key"))
                }
                if (withKey) androidx.compose.material3.Text("Make a key in your Audiobookshelf under Settings › API Keys. Keys you give no end date keep working.",
                    style = ReadType.body(), color = orbitLook().dim)
                else OrbitInputField(username, { username = it.take(100) }, "User name", modifier = Modifier.fillMaxWidth().testTag("books:abs:user"))
                OrbitInputField(password, { password = it.take(4000) }, if (withKey) "API key" else "Password", modifier = Modifier.fillMaxWidth().testTag("books:abs:password"),
                    secret = true)
                Spacer(Modifier.height(4.dp))
                ReadButton("Connect", { scope.action { scope.repository.audio.connectAudiobookshelf(server, username, password, apiKey = withKey); password = "" } },
                    enabled = server.isNotBlank() && password.isNotBlank() && (withKey || username.isNotBlank()) && !scope.busy, modifier = Modifier.testTag("books:abs:connect"))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
