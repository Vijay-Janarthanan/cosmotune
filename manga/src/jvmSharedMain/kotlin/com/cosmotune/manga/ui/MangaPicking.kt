package com.cosmotune.manga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.MangaBook
import com.cosmotune.manga.data.MangaPicks
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.LocalOrbitUndo
import com.cosmotune.ui.orbit.OrbitUndoState
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.removeAfterUndo

/** The series chosen by holding their covers, on whichever Manga page is open; choosing starts with a hold and ends with the bar's Close. */
@Stable
internal class MangaPicking {
    var chosen by mutableStateOf(emptyList<MangaBook>())
        private set
    val active: Boolean get() = chosen.isNotEmpty()
    fun has(book: MangaBook) = chosen.any { it.id == book.id }
    fun toggle(book: MangaBook) { chosen = MangaPicks.toggle(chosen, book) }
    fun clear() { chosen = emptyList() }
}

internal val LocalMangaPicking = staticCompositionLocalOf<MangaPicking?> { null }

/** Series taken out of the library that wait out their Undo: the library leaves them out meanwhile. */
internal object MangaPendingRemovals { var ids by mutableStateOf(emptySet<String>()) }

/** Adds [books] to the library ("Added 3 series" with Undo), or takes them out (gone after Undo has run out). */
internal fun mangaSetInLibrary(scope: MangaScope, undo: OrbitUndoState?, books: List<MangaBook>, add: Boolean) {
    if (books.isEmpty()) return
    val ids = books.map { it.id }.toSet()
    val what = if (books.size == 1) books.single().title else "${books.size} series"
    if (add) {
        scope.action { books.forEach { scope.repository.store.save(it) } }
        undo?.show(if (books.size == 1) "Added “$what”" else "Added $what") { scope.action { ids.forEach { scope.repository.removeBook(it) } } }
    } else undo.removeAfterUndo(what, hide = { MangaPendingRemovals.ids += ids }, restore = { MangaPendingRemovals.ids -= ids }) {
        ids.forEach { scope.repository.removeBook(it) }
        MangaPendingRemovals.ids -= ids
    }
}

/** The bar under a page while series are chosen: how many, Add to library and Remove from library for the ones that can, and Close. */
@Composable
internal fun MangaPickBar(scope: MangaScope, picking: MangaPicking, modifier: Modifier = Modifier) {
    val state by scope.repository.store.state.collectAsState()
    val look = orbitLook()
    val undo = LocalOrbitUndo.current
    val split = MangaPicks.split(picking.chosen, state.books.map { it.id }.toSet() - MangaPendingRemovals.ids)
    Column(modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 8.dp).clip(ReadShape.md).background(look.ground)
        .border(1.dp, look.ink.copy(alpha = 0.10f), ReadShape.md).padding(horizontal = 12.dp, vertical = 10.dp).testTag("manga:pick")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (picking.chosen.size == 1) mangaText("1 series chosen") else "${picking.chosen.size} " + mangaText("series chosen"), style = ReadType.rowTitle(),
                color = look.ink, modifier = Modifier.weight(1f).padding(start = 6.dp))
            ReadIconButton(CosmoIcons.Close, "Stop choosing", { picking.clear() })
        }
        ReadChipRow(Modifier.padding(top = 8.dp), inset = 0.dp) {
            if (split.toAdd.isNotEmpty()) ReadPill(mangaText("Add to library") + " · ${split.toAdd.size}", icon = CosmoIcons.Heart, onClick = {
                picking.clear(); mangaSetInLibrary(scope, undo, split.toAdd, add = true)
            }, modifier = Modifier.testTag("manga:pick:add"))
            if (split.toRemove.isNotEmpty()) ReadPill(mangaText("Remove from library") + " · ${split.toRemove.size}", icon = CosmoIcons.Close, onClick = {
                picking.clear(); mangaSetInLibrary(scope, undo, split.toRemove, add = false)
            }, modifier = Modifier.testTag("manga:pick:remove"))
        }
    }
}
