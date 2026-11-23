package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The sources Browse's feed shows, in the listener's order: the saved order first, then sources added since; hidden ones left out. */
internal fun mangaFeedSources(state: MangaLibraryState): List<MangaSourceConfig> {
    val enabled = state.sources.filter { it.enabled && MangaSourcePolicy.allowed(it) }
    val ordered = state.feedOrder.mapNotNull { id -> enabled.find { it.id == id } } + enabled.filter { it.id !in state.feedOrder }
    return ordered.filter { it.id !in state.feedHidden }.take(MANGA_FEED_SHELVES)
}

/** [ids] (the feed's order) with [id] moved one place up ([by] -1) or down (+1). */
internal fun mangaFeedMove(ids: List<String>, id: String, by: Int): List<String> {
    val index = ids.indexOf(id)
    val target = index + by
    if (index < 0 || target !in ids.indices) return ids
    return ids.toMutableList().apply { add(target, removeAt(index)) }
}

internal const val MANGA_FEED_SHELVES = 8

/**
 * Browse's feed (Komikku's Feed, Kotatsu's Explore): "For you" (what the sources suggest beside the series read last), the top of
 * the day, week or month on the first source that ranks them, then one shelf per source with its latest updates (its popular
 * series where it lists no updates). "Edit feed" orders and hides the shelves.
 */
@Composable
internal fun MangaFeed(scope: MangaScope) {
    val state by scope.repository.store.state.collectAsState()
    val sources = mangaFeedSources(state)
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("manga:feed"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        MangaForYou(scope, state)
        MangaTopShelf(scope, sources)
        sources.forEach { source -> key(source.id) { MangaFeedShelf(scope, source) } }
        ReadChipRow { ReadPill(mangaText("Edit feed"), icon = com.cosmotune.ui.components.CosmoIcons.Sort, onClick = { editing = true }, modifier = Modifier.testTag("manga:feed:edit")) }
    }
    if (editing) MangaFeedEditor(scope.repository) { editing = false }
}

@Composable
private fun MangaShelf(scope: MangaScope, title: String, books: List<MangaBook>?, error: String?, onSeeAll: (() -> Unit)?, modifier: Modifier = Modifier, header: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        ReadSection(title, onSeeAll = onSeeAll)
        header()
        when {
            books == null && error == null -> ReadSkeletonGrid(rows = 1)
            books.isNullOrEmpty() -> Text(error ?: mangaText("Nothing here right now."), style = ReadType.meta(), color = orbitLook().dim,
                modifier = Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 4.dp))
            else -> LazyRow(contentPadding = PaddingValues(horizontal = ReadMargin), horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                items(books.take(15), key = { "${title}:${it.id}" }) { book -> MangaBookCard(scope, book, null, width = 112.dp) }
            }
        }
    }
}

@Composable
private fun MangaFeedShelf(scope: MangaScope, source: MangaSourceConfig) {
    var books by remember(source) { mutableStateOf<List<MangaBook>?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var latest by remember(source) { mutableStateOf(true) }
    LaunchedEffect(source) {
        try {
            latest = MangaListOrder.LATEST in scope.repository.orders(source.id)
            books = scope.repository.catalogue(source.id, if (latest) MangaListOrder.LATEST else MangaListOrder.POPULAR).books
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This source could not be opened"; books = emptyList() }
    }
    MangaShelf(scope, (if (latest) mangaText("Latest on") else mangaText("Popular on")) + " " + source.name, books, error, { scope.navigation.source = source.id },
        Modifier.testTag("manga:feed:${source.id}"))
}

/** The top of the day, week or month on the first feed source that ranks its series by time. */
@Composable
private fun MangaTopShelf(scope: MangaScope, sources: List<MangaSourceConfig>) {
    var found by remember(sources) { mutableStateOf<Pair<MangaSourceConfig, Set<MangaListOrder>>?>(null) }
    LaunchedEffect(sources) {
        found = sources.firstNotNullOfOrNull { source -> scope.repository.orders(source.id).takeIf { MangaListOrder.TOP_WEEK in it }?.let { source to it } }
    }
    val (ranked, orders) = found ?: return
    var period by rememberSaveable { mutableStateOf(MangaListOrder.TOP_WEEK.name) }
    var books by remember(ranked, period) { mutableStateOf<List<MangaBook>?>(null) }
    var error by remember(ranked, period) { mutableStateOf<String?>(null) }
    LaunchedEffect(ranked, period) {
        try { books = scope.repository.catalogue(ranked.id, MangaListOrder.valueOf(period)).books }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "This source could not be opened"; books = emptyList() }
    }
    MangaShelf(scope, mangaText("Top on") + " " + ranked.name, books, error, null, Modifier.testTag("manga:feed:top")) {
        ReadChipRow(Modifier.padding(bottom = 8.dp)) {
            listOf(MangaListOrder.TOP_DAY to "Today", MangaListOrder.TOP_WEEK to "This week", MangaListOrder.TOP_MONTH to "This month").filter { it.first in orders }.forEach { (order, label) ->
                ReadChip(mangaText(label), period == order.name, { period = order.name })
            }
        }
    }
}

/** "For you": what the sources suggest beside the three series read last, without the ones already in the library. */
@Composable
private fun MangaForYou(scope: MangaScope, state: MangaLibraryState) {
    val seeds = remember(state.progress.keys, state.books.size) {
        state.progress.values.sortedByDescending { it.updatedAtMs }.mapNotNull { p -> state.books.find { it.id == p.bookId } }.filter { it.sourceId != "local" }.take(3)
    }
    if (seeds.isEmpty()) return
    var books by remember(seeds.map { it.id }) { mutableStateOf<List<MangaBook>?>(null) }
    LaunchedEffect(seeds.map { it.id }) {
        val found = seeds.flatMap { seed ->
            try { scope.repository.related(seed) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        }
        books = mangaForYou(found, state)
    }
    val list = books
    if (list != null && list.isEmpty()) return
    MangaShelf(scope, mangaText("For you"), list, null, null, Modifier.testTag("manga:feed:foryou"))
}

/** The "For you" list: suggestions in order, each once, never a series in the library or one marked not interested. */
internal fun mangaForYou(found: List<MangaBook>, state: MangaLibraryState): List<MangaBook> {
    val owned = state.books.map { it.id }.toSet()
    return found.distinctBy { it.id }.filter { it.id !in owned && it.id !in state.notInterested }.take(20)
}

/** Orders the feed's shelves and hides the ones not wanted. */
@Composable
private fun MangaFeedEditor(repository: MangaRepository, onDismiss: () -> Unit) {
    val state by repository.store.state.collectAsState()
    val look = orbitLook()
    val coroutines = rememberCoroutineScope()
    val enabled = state.sources.filter { it.enabled && MangaSourcePolicy.allowed(it) }
    val ordered = state.feedOrder.mapNotNull { id -> enabled.find { it.id == id } } + enabled.filter { it.id !in state.feedOrder }
    fun save(order: List<String>, hidden: Set<String>) = coroutines.launch { runCatching { repository.store.update { it.copy(feedOrder = order, feedHidden = hidden) } } }
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader(mangaText("Edit feed"), onDone = onDismiss)
        Text(mangaText("Choose which sources show a shelf, and in what order. At most $MANGA_FEED_SHELVES are shown."), style = ReadType.meta(), color = look.dim,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        ordered.forEach { source ->
            val ids = ordered.map { it.id }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("manga:feed:row:${source.id}"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.name, style = ReadType.rowTitle(), color = look.ink, modifier = Modifier.weight(1f).padding(start = 8.dp, end = 4.dp))
                ReadIconButton(Icons.Rounded.KeyboardArrowUp, "Move ${source.name} up", { save(mangaFeedMove(ids, source.id, -1), state.feedHidden) })
                ReadIconButton(Icons.Rounded.KeyboardArrowDown, "Move ${source.name} down", { save(mangaFeedMove(ids, source.id, 1), state.feedHidden) })
                OrbitSwitch(source.id !in state.feedHidden, onChange = { on -> save(ids, if (on) state.feedHidden - source.id else state.feedHidden + source.id) }, look = look,
                    label = "Show ${source.name} in the feed")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
