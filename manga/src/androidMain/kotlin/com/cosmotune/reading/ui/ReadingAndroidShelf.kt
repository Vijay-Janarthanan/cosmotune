package com.cosmotune.reading.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cosmotune.ui.collectAsUiState
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.manga.ui.AndroidMangaLibrary
import com.cosmotune.music.data.model.Song
import com.cosmotune.reading.data.ReadingAudioMath
import com.cosmotune.reading.data.ReadingAudiobook
import com.cosmotune.reading.data.ReadingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read for the rest of the phone app: the book being read for the home-screen widget, the Listen shelf for Android Auto (and
 * where each audiobook got to when it plays there), and a way to open a book from outside Read.
 */
object ReadingAndroidShelf {
    /** The book being read now, as the widget shows it. */
    data class NowReading(val bookId: String, val title: String, val line: String, val coverPath: String?, val fraction: Float)

    /** Told when what the widget shows may have changed (set by the app's widget). */
    @Volatile var onChange: ((Context) -> Unit)? = null

    private val opening = MutableStateFlow<String?>(null)
    /** A book to open in Read, asked for from outside it (the widget; blank for Read itself); Read opens it and clears this. */
    val pendingBook: StateFlow<String?> = opening.asStateFlow()
    fun open(bookId: String?) { opening.value = bookId?.takeIf { it.isEmpty() || it.matches(Regex("[0-9a-f]{64}")) } }
    internal fun opened() { opening.value = null }

    fun repository(context: Context): ReadingRepository = ReadingLibrary.of(AndroidMangaLibrary.repository(context.applicationContext))

    /** The book read most lately and not finished, or null. */
    fun nowReading(context: Context): NowReading? {
        val state = runCatching { repository(context).state.value }.getOrNull() ?: return null
        val book = state.books.filter { it.id in state.progress && it.id !in state.finished }.maxByOrNull { state.progress[it.id]?.updatedAtMs ?: 0L } ?: return null
        return NowReading(book.id, book.title, state.bookLine(book), book.coverPath, state.fraction(book) ?: 0f)
    }

    /** The Listen shelf's audiobooks with something to play, the latest heard first (for Android Auto). */
    fun audiobooks(context: Context): List<ReadingAudiobook> = runCatching { repository(context).state.value.audiobooks }.getOrDefault(emptyList())
        .filter { it.tracks.isNotEmpty() }.sortedByDescending { maxOf(it.listenedAtMs, it.addedAtMs) }

    /** [bookId]'s parts as the player's songs, with the part and the place to start from (where the listener was). */
    suspend fun queue(context: Context, bookId: String): Triple<List<Song>, Int, Long>? {
        val repository = runCatching { repository(context) }.getOrNull() ?: return null
        val found = repository.state.value.audiobooks.firstOrNull { it.id == bookId } ?: return null
        val book = runCatching { repository.audio.ready(found) }.getOrDefault(found)
        val (index, inTrack) = ReadingAudioMath.locate(book.tracks, if (book.finished) 0 else book.positionMs)
        val artwork = book.coverPath?.let { java.io.File(it).toURI().toString() } ?: book.coverUrl
        val songs = book.tracks.mapIndexed { i, track -> ReadingAudioItem(ReadingAudioHost.itemId(book.id, i), track.title.ifBlank { book.title }, book.author, track.playable, artwork).song() }
        return Triple(songs, index, inTrack)
    }

    /** Keeps where an audiobook got to while it plays outside Read (in the car): [playerId] is the player's item id. */
    suspend fun savePlace(context: Context, playerId: String?, positionMs: Long, sendRemote: Boolean) {
        val (bookId, index) = ReadingAudioHost.parse(playerId) ?: return
        val repository = runCatching { repository(context) }.getOrNull() ?: return
        val book = repository.state.value.audiobooks.firstOrNull { it.id == bookId } ?: return
        runCatching { repository.audio.savePosition(book.id, ReadingAudioMath.overall(book.tracks, index, positionMs), sendRemote) }
    }
}

/** Tells the widget when the book being read or its place changes, and opens a book the widget asked for. */
@Composable internal actual fun ReadingOutsideHooks(scope: ReadScope) {
    val context = LocalContext.current.applicationContext
    val state by scope.repository.state.collectAsUiState()
    val book = state.books.filter { it.id in state.progress && it.id !in state.finished }.maxByOrNull { state.progress[it.id]?.updatedAtMs ?: 0L }
    LaunchedEffect(book?.id, ((book?.let { state.fraction(it) } ?: 0f) * 100).toInt()) { ReadingAndroidShelf.onChange?.invoke(context) }
    val pending by ReadingAndroidShelf.pendingBook.collectAsState()
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        state.books.firstOrNull { it.id == id }?.let(scope.open)
        ReadingAndroidShelf.opened()
    }
}
