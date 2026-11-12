package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.SearchHistoryEntity
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPageButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

// What the phone's pages — Home, Explore, Library, Search, shared from :sharedUi —
// read from the app around them, answered from this computer's own stores.

/** The shared pages' settings and downloads, from the desktop's preferences. */
internal object DesktopAppUiHost : AppUiHost {

    private val persistence = DesktopPersistence()

    override val swipeToPlayNext: StateFlow<Boolean> =
        MutableStateFlow(persistence.boolean(KEY_SWIPE_PLAY_NEXT, false))

    private val _homeRecentsViewType = MutableStateFlow(
        runCatching { LibraryViewType.valueOf(persistence.string(KEY_HOME_RECENTS_VIEW, LibraryViewType.LIST.name)) }
            .getOrDefault(LibraryViewType.LIST),
    )
    override val homeRecentsViewType: StateFlow<LibraryViewType> = _homeRecentsViewType

    override fun setHomeRecentsViewType(value: LibraryViewType) {
        persistence.saveString(KEY_HOME_RECENTS_VIEW, value.name)
        _homeRecentsViewType.value = value
    }

    private val pins = MutableStateFlow(persistence.string("pinned_playlists").lineSequence().filter { it.isNotBlank() }.distinct().take(5).toList())
    override val pinnedPlaylists: StateFlow<List<String>> = pins

    fun togglePinnedPlaylist(id: String) {
        if (id.isBlank()) return
        val next = if (id in pins.value) pins.value - id else (listOf(id) + pins.value).take(5)
        persistence.saveString("pinned_playlists", next.joinToString("\n"))
        pins.value = next
    }

    private val _librarySort = MutableStateFlow(
        runCatching { LibrarySort.valueOf(persistence.string(KEY_LIBRARY_SORT, LibrarySort.DEFAULT.name)) }
            .getOrDefault(LibrarySort.DEFAULT),
    )
    override val librarySort: StateFlow<LibrarySort> = _librarySort

    override fun setLibrarySort(value: LibrarySort) {
        persistence.saveString(KEY_LIBRARY_SORT, value.name)
        _librarySort.value = value
    }

    /** Kept current by the app, which owns the download list — see `CosmotuneDesktopApp`. */
    val downloaded = MutableStateFlow<Set<String>>(emptySet())

    @Composable
    override fun downloadedIds(): Set<String> {
        val ids by downloaded.collectAsState()
        return ids
    }

    private const val KEY_SWIPE_PLAY_NEXT = "swipe_play_next"
    private const val KEY_HOME_RECENTS_VIEW = "home_recents_view"
}

/**
 * Recent searches as the phone keeps them: the thing that was picked — a track,
 * an album, an artist, a playlist — with its artwork, rather than the words
 * typed to find it. Tapping one goes straight back to it.
 */
internal object DesktopSearchHistory {

    private val persistence = DesktopPersistence()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SearchHistoryEntity.serializer())

    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<SearchHistoryEntity>> = _recent

    /** Puts [entity] at the top, dropping any earlier record of the same thing. */
    fun record(entity: SearchHistoryEntity) {
        if (entity.id.isBlank()) return
        save((listOf(entity) + _recent.value.filterNot { it.id == entity.id }).take(MAX_ENTRIES))
    }

    fun remove(id: String) = save(_recent.value.filterNot { it.id == id })

    fun clear() = save(emptyList())

    private fun save(value: List<SearchHistoryEntity>) {
        _recent.value = value
        persistence.saveString(KEY, json.encodeToString(serializer, value))
    }

    private fun load(): List<SearchHistoryEntity> =
        runCatching { json.decodeFromString(serializer, persistence.string(KEY, "[]")) }.getOrDefault(emptyList())

    private const val KEY = "search_entities"
    private const val MAX_ENTRIES = 20
}

/** The words searched in the Add-ons source, newest first (the rules are [com.cosmotune.music.data.search.AddonQueryHistory]'s). */
internal object DesktopAddonSearchHistory {
    private val persistence = DesktopPersistence()
    private val history = com.cosmotune.music.data.search.AddonQueryHistory(
        read = { persistence.string(KEY, "[]") },
        write = { persistence.saveString(KEY, it) },
    )
    val recent: StateFlow<List<String>> get() = history.recent
    fun record(query: String) = history.record(query)
    fun remove(query: String) = history.remove(query)
    fun clear() = history.clear()
    private const val KEY = "addon_search_history"
}

/** The remembered "Search in" choice of the Search page (All, YouTube Music or one source), shared rules in AddonSearchChoice. */
internal object DesktopSearchIn {
    private val persistence = DesktopPersistence()
    private val choice = com.cosmotune.music.data.search.AddonSearchChoice(
        read = { persistence.string(KEY, "").ifBlank { null } },
        write = { persistence.saveString(KEY, it) },
    )
    val selected: StateFlow<String> get() = choice.selected
    val lastAddonChoice: String get() = choice.lastAddonChoice
    fun select(value: String) = choice.select(value)
    private const val KEY = "search_in_source"
}

/**
 * Opens a page's menus where the pointer is.
 *
 * The shared pages say "this row was held" — a long press on the phone, a
 * right-click here — without saying where it was; the desktop's menus hang off
 * an anchor. So this watches the pointer over the page without taking any of
 * its events, and plants a one-point anchor wherever it last was when a menu is
 * asked for.
 *
 * [menu] draws the menu for whatever was asked for; [content] is handed the
 * call that asks.
 */
@Composable
internal fun <T : Any> DesktopPointerMenuHost(
    menu: @Composable (target: T, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (open: (T) -> Unit) -> Unit,
) {
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf<Pair<T, Offset>?>(null) }
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.let { pointer = it.position }
                    }
                }
            },
    ) {
        content { item -> target = item to pointer }
        target?.let { (item, at) ->
            Box(
                Modifier
                    .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .size(1.dp),
            ) {
                menu(item) { target = null }
            }
        }
    }
}

/**
 * The phone's sort for a Library "Show all" grid: the one thing its cards carry is a title. The button is the page's round Orbit
 * button and the choices an [OrbitMenu], the one in force solid ink.
 */
@Composable
internal fun DesktopLibrarySortMenu() {
    val sort by DesktopAppUiHost.librarySort.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        OrbitPageButton(CosmoIcons.Sort, DesktopStrings["sort_library", "Sort library"], onClick = { open = true }, on = open)
        OrbitMenu(
            open = open,
            onDismiss = { open = false },
            items = LibrarySort.entries.map { option ->
                OrbitMenuItem(librarySortLabel(option), selected = option == sort) { DesktopAppUiHost.setLibrarySort(option) }
            },
            fonts = orbitFonts(),
            look = orbitLook(),
            width = 240.dp,
        )
    }
}

/** A sort's name, as the phone's menu gives it. */
private fun librarySortLabel(option: LibrarySort): String = when (option) {
    LibrarySort.DEFAULT -> DesktopStrings["sort_default", "Default order"]
    LibrarySort.TITLE_ASC -> DesktopStrings["sort_title_ascending", "Alphabetical (A to Z)"]
    LibrarySort.TITLE_DESC -> DesktopStrings["sort_title_descending", "Alphabetical (Z to A)"]
}

