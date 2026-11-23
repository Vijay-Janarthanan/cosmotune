package com.cosmotune.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitReaderButton
import com.cosmotune.ui.orbit.OrbitReaderCard
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Listening along: the words are heard on the app's player while the page follows. Two ways: the book's own recorded narration
 * (EPUB 3 media overlays), marking each phrase as it is spoken and turning the chapters; or an audiobook of the same book on the
 * Listen shelf, started at the page's chapter and place, turning the page when the recording reaches the next chapter.
 */
internal class ReadAlong(private val host: ReadingAudioHost, private val page: ReadingPageControl, private val scope: CoroutineScope,
    private val document: ReadingDocument.Epub, private val navigate: (ReadingLocator) -> Unit, private val onError: (String) -> Unit) {
    var active by mutableStateOf(false); private set
    /** The audiobook paired with the book, while listening to it; null for the book's own narration. */
    var audiobook by mutableStateOf<ReadingAudiobook?>(null); private set
    var label by mutableStateOf(""); private set
    var progress by mutableFloatStateOf(0f); private set
    private var job: Job? = null
    private var section = 0
    private val overlayPrefix get() = "along:${document.book.id}:"

    val playing: Boolean get() = active && host.playing

    /** The book's own narration from the page's place (or the chapter's start). */
    fun startNarration() {
        val here = document.spine.indexOfFirst { it.href == page.href }.coerceAtLeast(0)
        val first = (here until document.spine.size).firstOrNull { document.overlays.containsKey(document.spine[it].href) }
        if (first == null) { onError("This book has no recorded narration from here on."); return }
        audiobook = null
        active = true
        job?.cancel()
        job = scope.launch {
            try {
                val fragment = if (first == here) page.run(ReadingEpubScripts.firstVisibleId)?.trim('"')?.ifBlank { null } else null
                narrate(first, fragment)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: "Listening along stopped."); stop() }
        }
    }

    /** [book] (paired with this one) from the place matching the page, [share] of the way through its chapter. */
    fun startAudiobook(book: ReadingAudiobook, share: Double, fresh: suspend (ReadingAudiobook) -> ReadingAudiobook) {
        if (book.tracks.isEmpty()) { onError("This audiobook has nothing to play."); return }
        audiobook = book
        active = true
        job?.cancel()
        job = scope.launch {
            try {
                val ready = fresh(book)
                audiobook = ready
                val chapters = ReadingAudioMath.chapters(ready)
                val pairs = ReadingAlongMatch.chapters(document.spine.map { it.title }, chapters.map { it.title })
                section = document.spine.indexOfFirst { it.href == page.href }.coerceAtLeast(0)
                val chapter = pairs.getOrElse(section) { 0 }
                val start = chapters.getOrNull(chapter)?.startMs ?: 0L
                val end = chapters.getOrNull(chapter + 1)?.startMs ?: ready.durationMs
                // Within the chapter, the page's share of it gives the share of the recording's chapter.
                host.playBook(ready, start + ((end - start) * share.coerceIn(0.0, 1.0)).toLong())
                follow(ready, pairs)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.message ?: "Listening along stopped."); stop() }
        }
    }

    fun toggle() { if (active) host.toggle() }

    /** Back or ahead by [ms] in the recording. */
    fun skip(ms: Long) { if (active) host.seek((host.positionMs + ms).coerceAtLeast(0)) }

    /** Stops following when the reader closes; a paired audiobook plays on (it is on the app's player), the narration stops. */
    fun leave() {
        if (audiobook == null) { stop(); return }
        job?.cancel(); job = null
        active = false
        audiobook = null
    }

    fun stop() {
        job?.cancel(); job = null
        if (active && host.playing && (ReadingAudioHost.parse(host.nowId)?.first?.let { it.startsWith(overlayPrefix) || it == audiobook?.id } == true)) host.toggle()
        active = false
        audiobook = null
        scope.launch { page.run(ReadingEpubScripts.narrating(null, document.overlayClass)) }
    }

    /** Plays the narration of chapter [index] from [fragment], marking each phrase, then the chapters after it. */
    private suspend fun narrate(start: Int, fragment: String?) {
        var index = start
        var from = fragment
        while (currentCoroutineContext().isActive) {
            val section = document.spine[index]
            val overlay = document.overlays.getValue(section.href)
            if (page.href != section.href) turnTo(section.href)
            val files = overlay.audio
            val clip = overlay.clips[overlay.clipFor(from)]
            val id = overlayPrefix + index
            host.play(files.mapIndexed { i, href ->
                ReadingAudioItem(ReadingAudioHost.itemId(id, i), section.title.ifBlank { document.book.title }, document.book.authors.joinToString(", "),
                    document.resources.getValue(href).file.path, document.book.coverPath?.let { java.io.File(it).toURI().toString() })
            }, files.indexOf(clip.audio).coerceAtLeast(0), clip.beginMs)
            label = section.title.ifBlank { "Chapter ${index + 1}" }
            // Wait for the player to take it, then follow it until the chapter's last phrase is spoken.
            withTimeoutOrNull(10_000) { snapshotFlow { ReadingAudioHost.parse(host.nowId)?.first }.first { it == id } } ?: error("The narration could not start.")
            var marked = -1
            val last = overlay.clips.last()
            while (true) {
                delay(120)
                val (now, file) = ReadingAudioHost.parse(host.nowId) ?: return stop()
                if (now != id) return stop()
                val href = files.getOrNull(file) ?: return stop()
                val position = host.positionMs
                val at = overlay.clipAt(href, position)
                if (at != null && at != marked) {
                    marked = at
                    progress = at / overlay.clips.size.coerceAtLeast(1).toFloat()
                    if (page.href == section.href) page.run(ReadingEpubScripts.narrating(overlay.clips[at].fragment, document.overlayClass))
                }
                val pastLast = href == last.audio && last.endMs > 0 && position >= last.endMs
                val ended = !host.playing && !host.loading && href == last.audio && host.durationMs > 0 && position >= host.durationMs - 500
                if (pastLast || ended) break
            }
            val next = (index + 1 until document.spine.size).firstOrNull { document.overlays.containsKey(document.spine[it].href) }
            if (next == null) { page.run(ReadingEpubScripts.narrating(null, document.overlayClass)); if (host.playing) host.toggle(); active = false; return }
            index = next
            from = null
        }
    }

    /** Follows the paired audiobook: when the recording reaches another chapter, the page turns to the book's matching one. */
    private suspend fun follow(book: ReadingAudiobook, pairs: List<Int>) {
        withTimeoutOrNull(10_000) { snapshotFlow { ReadingAudioHost.parse(host.nowId)?.first }.first { it == book.id } } ?: error("The audiobook could not start.")
        val chapters = ReadingAudioMath.chapters(book)
        var heard = -1
        while (true) {
            delay(500)
            val position = host.positionIn(book) ?: return stop()
            val chapter = ReadingAudioMath.chapterAt(book, position)
            val start = chapters.getOrNull(chapter)?.startMs ?: 0L
            val end = chapters.getOrNull(chapter + 1)?.startMs ?: book.durationMs
            progress = if (end > start) ((position - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
            label = chapters.getOrNull(chapter)?.title.orEmpty()
            if (chapter != heard) {
                // The first chapter heard is the page's own; later ones turn the page.
                if (heard >= 0) ReadingAlongMatch.section(pairs, chapter)?.takeIf { it != section }?.let { target ->
                    section = target
                    turnTo(document.spine[target].href)
                }
                heard = chapter
            }
        }
    }

    private suspend fun turnTo(href: String) {
        val before = page.loads
        navigate(ReadingLocator(resourceHref = href))
        withTimeoutOrNull(15_000) { snapshotFlow { page.loads to page.href }.first { (loads, at) -> loads > before && at == href } }
        delay(250)
    }
}

/** The listening-along card at the page's foot, in the book's colour: stop, the chapter, back 30 s, play or pause, ahead 30 s. */
@Composable
internal fun ReadAlongCard(along: ReadAlong, fill: Color, ink: Color) {
    OrbitReaderCard(
        label = (if (along.audiobook != null) "Listening along · " else "Narration · ") + along.label,
        progress = { along.progress }, fill = fill, ink = ink,
        leading = { OrbitReaderButton(CosmoIcons.Close, "Stop listening along", ink, fill, along::stop, Modifier.testTag("books:along:stop")) },
        modifier = Modifier.testTag("books:along"),
    ) {
        OrbitReaderButton(Icons.Rounded.Replay30, "Back 30 seconds", ink, fill, { along.skip(-30_000) }, Modifier.testTag("books:along:back"))
        OrbitReaderButton(if (along.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (along.playing) "Pause" else "Play", ink, fill,
            along::toggle, Modifier.testTag("books:along:play"), on = true)
        OrbitReaderButton(Icons.Rounded.Forward30, "Ahead 30 seconds", ink, fill, { along.skip(30_000) }, Modifier.testTag("books:along:ahead"))
    }
}

/**
 * Choosing how to listen along: the book's own narration when it has one, and the Listen shelf's audiobooks to pair with it
 * (the likeliest first: the same title), or to leave it unpaired.
 */
@Composable
internal fun ReadAlongSheet(state: ReadingState, book: ReadingBook, hasNarration: Boolean, onNarration: () -> Unit, onPair: (ReadingAudiobook?) -> Unit,
    onListen: (ReadingAudiobook) -> Unit, onDismiss: () -> Unit) {
    val look = orbitLook()
    val paired = state.audioPairs[book.id]?.let { id -> state.audiobooks.firstOrNull { it.id == id } }
    fun key(text: String) = text.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    val shelf = state.audiobooks.sortedWith(compareByDescending<ReadingAudiobook> { it.id == paired?.id }
        .thenByDescending { key(it.title) == key(book.title) || key(book.title).startsWith(key(it.title)) || key(it.title).startsWith(key(book.title)) }
        .thenBy { it.title.lowercase() })
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Listen along", onDone = onDismiss, doneLabel = "Close")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("books:along:sheet")) {
            if (hasNarration) item(key = "narration") {
                ReadSheetRow("The book's own narration", Icons.Rounded.Headphones, { onNarration(); onDismiss() }, Modifier.testTag("books:along:narration"))
            }
            if (paired != null) item(key = "paired") {
                ReadSheetRow("Listen to ${paired.title}", CosmoIcons.Play, { onListen(paired); onDismiss() }, Modifier.testTag("books:along:listen"))
            }
            item(key = "head") {
                Text(if (shelf.isEmpty()) "Add this book's audiobook in Read › Listen (from this device, your Audiobookshelf or LibriVox), then pair it here."
                    else "Pair an audiobook of this book: the page then follows the recording chapter by chapter.",
                    style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            items(shelf, key = { it.id }) { audio ->
                ReadSheetRow(audio.title, if (audio.id == paired?.id) CosmoIcons.Check else null, {
                    if (audio.id == paired?.id) onPair(null) else { onPair(audio); onListen(audio); onDismiss() }
                }, Modifier.testTag("books:along:pair:${audio.id}"),
                    trailing = { Text(if (audio.id == paired?.id) "Paired · tap to unpair" else audio.author, style = orbitFonts().label, color = look.dim, maxLines = 1) })
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
