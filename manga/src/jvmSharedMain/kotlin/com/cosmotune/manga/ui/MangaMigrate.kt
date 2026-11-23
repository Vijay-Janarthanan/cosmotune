package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Find a series on another source (Mihon's migration, Kotatsu's alternatives): every other enabled source is searched for its
 * title, the closest matches come first with their source, and choosing one moves the series there with its read chapters,
 * place, collections and reading-list links. Used when a source stops working, and for series from a Mihon backup.
 */
@Composable
internal fun MangaMigrateSheet(scope: MangaScope, book: MangaBook, onDismiss: () -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val look = orbitLook()
    val coroutines = rememberCoroutineScope()
    var query by remember(book.id) { mutableStateOf(book.title) }
    var search by remember(book.id) { mutableIntStateOf(1) }
    var results by remember(book.id) { mutableStateOf(MangaSearchResults()) }
    var chosen by remember(book.id) { mutableStateOf<MangaBook?>(null) }
    var preview by remember(book.id) { mutableStateOf<MangaBook?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val targets = state.sources.filter { it.enabled && it.id != book.sourceId && MangaSourcePolicy.allowed(it) }
    LaunchedEffect(search) {
        val term = query.trim()
        if (term.length < 2 || targets.isEmpty()) return@LaunchedEffect
        message = null
        try { mangaGlobalSearch(targets, term) { config, text -> repository.browse(config.id, text, 0) }.collect { results = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message = failure.message }
    }
    val matches = remember(results) { mangaMigrationMatches(book, results.books) }
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader(mangaText("Find on another source"), onDone = onDismiss, doneLabel = "Close")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("manga:migrate"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val pick = chosen
            if (pick == null) {
                if (targets.isEmpty()) Text(mangaText("Turn on another source in Sources first, then search again."), style = ReadType.meta(), color = look.dim)
                else {
                    OrbitInputField(query, { query = it.take(200) }, mangaText("Title"), modifier = Modifier.fillMaxWidth().testTag("manga:migrate:query"))
                    ReadButton(mangaText("Search again"), { search++ }, enabled = query.trim().length >= 2, kind = ReadKind.TONAL, modifier = Modifier.padding(top = 4.dp))
                    Text(if (results.completed < results.total) "Searching ${results.completed} of ${results.total} sources…"
                        else if (matches.isEmpty()) mangaText("No match found. Try a shorter or different title.") else "${matches.size} matches", style = ReadType.meta(), color = look.dim)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(matches, key = { it.book.id }) { match ->
                        val cover = rememberMangaCover(repository, match.book)
                        val source = state.sources.find { it.id == match.book.sourceId }?.name.orEmpty()
                        ReadCoverRow(match.book.title, source, cover, key = match.book.id, modifier = Modifier.testTag("manga:migrate:match:${match.book.id}"),
                            onClick = { chosen = match.book; preview = null; coroutines.launch {
                                busy = true; message = null
                                try { preview = repository.chapters(match.book) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { message = failure.message ?: "This series could not be opened" }
                                finally { busy = false }
                            } })
                    }
                }
            } else {
                val source = state.sources.find { it.id == pick.sourceId }?.name.orEmpty()
                Text(pick.title, style = ReadType.rowTitle(), color = look.ink)
                val detail = preview
                Text(when {
                    detail == null && busy -> "Loading its chapters on $source…"
                    detail == null -> "Its chapters could not be loaded on $source."
                    else -> mangaMigrationSummary(book, detail, state).let { summary ->
                        "${summary.chapters} chapters on $source. " + if (summary.readBefore == 0) "Your collections come along."
                            else "${summary.read} of your ${summary.readBefore} read chapters are found there and stay read; your place and collections come along."
                    }
                }, style = ReadType.meta(), color = look.dim)
                ReadButton(mangaText(if (busy && detail != null) "Moving…" else "Move to $source"), enabled = detail != null && !busy, modifier = Modifier.padding(top = 4.dp).testTag("manga:migrate:move"),
                    onClick = { coroutines.launch {
                        busy = true; message = null
                        try {
                            val moved = repository.migrate(book, requireNotNull(detail))
                            scope.navigation.book = moved
                            onDismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { message = failure.message ?: "The series could not be moved" }
                        finally { busy = false }
                    } })
                ReadTextButton(mangaText("Choose another match"), { chosen = null; preview = null })
            }
            message?.let { Text(it, style = ReadType.meta(), color = look.ink) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
