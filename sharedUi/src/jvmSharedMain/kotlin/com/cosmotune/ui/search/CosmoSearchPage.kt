package com.cosmotune.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.LocalOrbitUndo
import com.cosmotune.ui.orbit.OrbitBusyRing
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitSearchPill
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitPageSide
import com.cosmotune.ui.orbit.removeAfterUndo

/*
 * One search page for every mode (the search-spotlight board, item 2): the same order everywhere, from the top: a small eyebrow
 * (the mode) over the big "Search." title, the box, a row of kind chips, an optional filter row (dropdown chips), then the count of
 * results with Sort at its end, then the results. Before anything is typed: Recent searches (each removable, "Clear" with Undo)
 * and the mode's Browse section. While a search runs it says "Searching…" in place; when nothing matches, a short message and
 * "Clear filters" if filters are set; when it fails, the message and "Try again". A mode plugs in only its words, its chips and
 * filters, its own results (a list or a grid) and what it offers to browse. See search-scaffold-api.md beside the worktrees.
 */

/** One chip of the kind row, or one choice of a filter: its [id], its [label], and a [count] shown after it when known. */
@Immutable
data class CosmoSearchChoice(val id: String, val label: String, val count: Int? = null)

/**
 * A filter of the filter row: a dropdown chip named [label] while its first option (the "any" one: "Any country") is chosen, and
 * the chosen option's name, solid ink, otherwise. Only a filter the source can really apply belongs here.
 */
@Immutable
class CosmoSearchFilter(
    val id: String,
    val label: String,
    val options: List<CosmoSearchChoice>,
    val selected: String,
    val onSelect: (String) -> Unit,
) {
    /** Whether something other than the first ("any") option is chosen. */
    val active: Boolean get() = options.isNotEmpty() && selected != options.first().id
}

/** One recent search: what it is called, and a cover for one that is a thing (Music's artist, album or song) rather than words. */
@Immutable
data class CosmoRecent(val id: String, val title: String, val artwork: String? = null)

/** The recent searches and what tapping, removing and clearing them does. "Clear" hides them at once and clears after the Undo. */
@Immutable
class CosmoSearchRecents(
    val items: List<CosmoRecent>,
    val onPick: (CosmoRecent) -> Unit,
    val onRemove: (CosmoRecent) -> Unit,
    val onClear: () -> Unit,
)

/** What the page shows under its controls. */
sealed interface CosmoSearchStatus {
    /** Nothing typed: the recent searches, then Browse. */
    data object Idle : CosmoSearchStatus
    /** Being typed: the mode's suggestions ([CosmoSearchPage]'s `typing`). */
    data object Typing : CosmoSearchStatus
    /** Asked and not answered yet: "Searching…" in place of the count; the results slot stays (sources may answer one by one). */
    data object Searching : CosmoSearchStatus
    /** Answered: "N results" (or [label]) with Sort at its end, then the results. */
    data class Results(val count: Int, val label: String? = null) : CosmoSearchStatus
    /** Answered with nothing: [message] (or "No results for “query”"), and "Clear filters" when filters are set. */
    data class Empty(val message: String? = null) : CosmoSearchStatus
    /** Failed: [message], and "Try again". */
    data class Failed(val message: String, val onRetry: () -> Unit) : CosmoSearchStatus
}

/**
 * The page's hold on its box: [request] focuses it and opens the keyboard (the mode's Search button pressed again while on Search
 * clears the box and calls this).
 */
@Stable
class CosmoSearchFocus {
    val requester = FocusRequester()
    var requests by mutableIntStateOf(0)
        private set

    fun request() { requests++ }
}

@Composable
fun rememberCosmoSearchFocus(focusOnOpen: Boolean = true): CosmoSearchFocus = remember { CosmoSearchFocus().also { if (focusOnOpen) it.request() } }

/** "1 result", "24 results". */
fun searchCountLabel(count: Int, one: String = "result", many: String = "results"): String = "$count ${if (count == 1) one else many}"

/**
 * The search page. [eyebrow] is the small mono line over the title (the mode's name, or what is searched: "Your library and every
 * source"); [title] the big word ("Search"). [contentPadding] is the room the shell keeps: its top above the head, its bottom under
 * the last row. [showHead] false leaves the title out (a host with a title of its own); [showField] false leaves the box out (the
 * desktop's header holds it). [leading] stands above the head (a back row on a pushed page).
 *
 * [kinds] is the kind row (the first is usually "All"), [kind] the chosen one; [filters] the dropdown row, drawn once something has
 * been searched; [sort] a dropdown at the end of the count line. [onClearFilters] is offered when nothing matches. [recents] and
 * [browse] fill the page before anything is typed ([browseTitle] heads the mode's Browse items, each a list item it adds). [typing]
 * draws the suggestions while the box is being edited; [results] draws the mode's results in the room left (its modifier carries the
 * weight), with the page's side margin.
 */
@Composable
fun CosmoSearchPage(
    eyebrow: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    status: CosmoSearchStatus,
    modifier: Modifier = Modifier,
    focus: CosmoSearchFocus = rememberCosmoSearchFocus(),
    contentPadding: PaddingValues = PaddingValues(),
    title: String = "Search",
    showHead: Boolean = true,
    showField: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    fieldTrailing: (@Composable RowScope.() -> Unit)? = null,
    kinds: List<CosmoSearchChoice> = emptyList(),
    kind: String? = null,
    onKind: (String) -> Unit = {},
    extraChips: (@Composable RowScope.() -> Unit)? = null,
    filters: List<CosmoSearchFilter> = emptyList(),
    sort: CosmoSearchFilter? = null,
    onClearFilters: (() -> Unit)? = null,
    recents: CosmoSearchRecents? = null,
    browseTitle: String = "Browse",
    browse: (LazyListScope.(side: Dp) -> Unit)? = null,
    columnMaxWidth: Dp = Dp.Unspecified,
    typing: (@Composable (modifier: Modifier, side: Dp) -> Unit)? = null,
    results: @Composable (modifier: Modifier, side: Dp) -> Unit = { _, _ -> },
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focus.requests) {
        if (focus.requests > 0 && showField) {
            runCatching { focus.requester.requestFocus() }
            keyboard?.show()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag(SEARCH_PAGE_TAG), contentAlignment = Alignment.TopCenter) {
        val side = orbitPageSide(maxWidth)
        val bottom = contentPadding.calculateBottomPadding()
        val column = if (columnMaxWidth != Dp.Unspecified) Modifier.widthIn(max = columnMaxWidth).fillMaxWidth() else Modifier.fillMaxWidth()
        Column(column.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            leading?.invoke()
            if (showHead) SearchHead(eyebrow, title, fonts, look, Modifier.padding(start = side + 4.dp, end = side, top = 8.dp))
            if (showField) {
                OrbitSearchPill(
                    query = query, onQueryChange = onQueryChange, onSubmit = onSubmit, placeholder = placeholder, fonts = fonts, look = look,
                    focusRequester = focus.requester, trailing = fieldTrailing,
                    modifier = Modifier.padding(start = side, end = side, top = if (showHead) 12.dp else 8.dp).testTag("search:field"),
                )
            }
            if (kinds.isNotEmpty() || extraChips != null) {
                OrbitChipRow(side, Modifier.padding(top = 12.dp).testTag("search:kinds")) {
                    extraChips?.invoke(this)
                    kinds.forEach { choice ->
                        OrbitSelectChip(choice.label, choice.id == kind, fonts, look, { onKind(choice.id) }, count = choice.count, haptic = choice.id != kind,
                            modifier = Modifier.semantics { if (choice.id == kind) stateDescription = "Selected" })
                    }
                }
            }
            val answered = status !is CosmoSearchStatus.Idle && status !is CosmoSearchStatus.Typing
            if (answered && filters.isNotEmpty()) {
                OrbitChipRow(side, Modifier.padding(top = 8.dp).testTag("search:filters")) {
                    filters.forEach { filter -> SearchFilterChip(filter, fonts, look) }
                }
            }
            when (status) {
                CosmoSearchStatus.Idle -> IdleList(recents, browseTitle, browse, side, bottom, fonts, look, Modifier.weight(1f).fillMaxWidth())
                CosmoSearchStatus.Typing -> (typing ?: results)(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), side)
                CosmoSearchStatus.Searching, is CosmoSearchStatus.Results -> {
                    CountLine(status, sort, side, fonts, look)
                    results(Modifier.weight(1f).fillMaxWidth(), side)
                }
                is CosmoSearchStatus.Empty -> Message(
                    title = status.message ?: if (query.isBlank()) "Nothing found" else "No results for “${query.trim()}”",
                    hint = if (onClearFilters != null) "Check the spelling or try fewer filters." else "Check the spelling or try other words.",
                    action = onClearFilters?.let { "Clear filters" to it }, side = side, fonts = fonts, look = look,
                    modifier = Modifier.testTag("search:empty"),
                )
                is CosmoSearchStatus.Failed -> Message(
                    title = "Search didn't work", hint = status.message, action = "Try again" to status.onRetry, side = side, fonts = fonts, look = look,
                    modifier = Modifier.testTag("search:error"),
                )
            }
        }
    }
}

/** The small mono eyebrow over the 30 sp title with its peach full stop (Home's heading). */
@Composable
private fun SearchHead(eyebrow: String, title: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    Column(modifier.fillMaxWidth().testTag("search:head")) {
        if (eyebrow.isNotBlank()) OrbitLabel(eyebrow, fonts, look.dim, Modifier.padding(bottom = 4.dp))
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(title)
                pushStyle(androidx.compose.ui.text.SpanStyle(color = look.accent)); append("."); pop()
            },
            style = fonts.greeting, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** "N results" (or "Searching…" with the small orbit), and Sort at the end of the line. */
@Composable
private fun CountLine(status: CosmoSearchStatus, sort: CosmoSearchFilter?, side: Dp, fonts: OrbitFonts, look: OrbitLook) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = side + 4.dp, end = side, top = 4.dp).testTag("search:count"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status is CosmoSearchStatus.Searching) {
            OrbitBusyRing(size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text("Searching…", style = fonts.body, color = look.dim, modifier = Modifier.weight(1f).testTag("search:searching"))
        } else if (status is CosmoSearchStatus.Results) {
            Text(status.label ?: searchCountLabel(status.count), style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
        if (sort != null) {
            Spacer(Modifier.width(12.dp))
            SearchFilterChip(sort, fonts, look, Modifier.testTag("search:sort"))
        }
    }
}

/** A filter as a dropdown chip: its name (or the chosen option, solid ink) and a chevron; the menu of its options under it. */
@Composable
fun SearchFilterChip(filter: CosmoSearchFilter, fonts: OrbitFonts = orbitFonts(), look: OrbitLook = orbitLook(), modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val active = filter.active
    val chosen = filter.options.firstOrNull { it.id == filter.selected }
    val text = if (active) chosen?.label ?: filter.label else filter.label
    val ink = if (active) look.ground else look.ink
    Box(modifier) {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(if (active) look.ink else look.chip)
                .clickable(role = Role.DropdownList, onClickLabel = "Choose ${filter.label}") { open = true }
                .semantics { stateDescription = "${filter.label}: ${chosen?.label ?: ""}" }
                .padding(start = 16.dp, end = 12.dp)
                .testTag("search:filter:${filter.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = fonts.chip, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            Spacer(Modifier.width(4.dp))
            Icon(CosmoIcons.ChevronDown, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        }
        OrbitMenu(
            open, { open = false },
            filter.options.map { option -> OrbitMenuItem(option.label, selected = option.id == filter.selected) { filter.onSelect(option.id) } },
            fonts, look, header = filter.label,
        )
    }
}

/** Before anything is typed: the recent searches (with Clear), then the mode's Browse items under [browseTitle]. */
@Composable
private fun IdleList(
    recents: CosmoSearchRecents?, browseTitle: String, browse: (LazyListScope.(Dp) -> Unit)?, side: Dp, bottom: Dp,
    fonts: OrbitFonts, look: OrbitLook, modifier: Modifier,
) {
    val undo = LocalOrbitUndo.current
    // "Clear" hides the list at once; it is cleared for good once its Undo has run out.
    var hidden by remember { mutableStateOf(false) }
    val items = if (hidden) emptyList() else recents?.items.orEmpty()
    LazyColumn(modifier.testTag("search:idle"), contentPadding = PaddingValues(top = 8.dp, bottom = bottom)) {
        if (recents != null && items.isNotEmpty()) {
            item(key = "recent:header") {
                Row(Modifier.fillMaxWidth().padding(start = side + 4.dp, end = side, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OrbitLabel("Recent", fonts, look.dim, Modifier.weight(1f))
                    Text(
                        "Clear", style = fonts.chip.copy(fontSize = 13.sp), color = look.ink,
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) {
                            undo.removeAfterUndo("recent searches", hide = { hidden = true }, restore = { hidden = false }, commit = { recents.onClear(); hidden = false })
                        }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("search:recent:clear"),
                    )
                }
            }
            item(key = "recent:chips") {
                OrbitChipRow(side, Modifier.padding(top = 8.dp).testTag("search:recent")) {
                    items.forEach { recent -> key(recent.id) { RecentChip(recent, fonts, look, { recents.onPick(recent) }, { recents.onRemove(recent) }) } }
                }
            }
        }
        if (browse != null) {
            item(key = "browse:header") {
                OrbitLabel(browseTitle, fonts, look.dim, Modifier.padding(start = side + 4.dp, end = side, top = if (items.isEmpty()) 8.dp else 24.dp, bottom = 8.dp))
            }
            browse(side)
        }
        if (items.isEmpty() && browse == null) item(key = "idle:hint") {
            Text("Your recent searches will show here.", style = fonts.body, color = look.dim, modifier = Modifier.padding(horizontal = side + 4.dp, vertical = 16.dp))
        }
    }
}

/** A recent search as a chip: its cover as a small disc (a clock for words alone), the words, and a cross that removes it. */
@Composable
internal fun RecentChip(recent: CosmoRecent, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(look.chip).clickable(role = Role.Button, onClick = onClick).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (recent.artwork != null) Disc(recent.artwork, 24.dp) else Icon(CosmoIcons.Clock, contentDescription = null, tint = look.dim, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(recent.title, style = fonts.chip, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        Box(Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(CosmoIcons.Close, contentDescription = "Remove ${recent.title}", tint = look.dim, modifier = Modifier.size(14.dp))
        }
    }
}

/** Nothing found, or a failure: the words, a short hint 8 dp under them, and the one action 16 dp under that. */
@Composable
private fun Message(title: String, hint: String, action: Pair<String, () -> Unit>?, side: Dp, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(start = side + 4.dp, end = side, top = 24.dp)) {
        Text(title, style = fonts.title, color = look.ink)
        if (hint.isNotBlank()) Text(hint, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 8.dp))
        if (action != null) CosmoButton(action.first, action.second, Modifier.padding(top = 16.dp), kind = CosmoButtonKind.QUIET, size = CosmoButtonSize.SMALL)
    }
}

/** The test tag of every mode's search page. */
const val SEARCH_PAGE_TAG = "search:page"
