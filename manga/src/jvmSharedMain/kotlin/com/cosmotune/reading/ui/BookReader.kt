package com.cosmotune.reading.ui

import com.cosmotune.manga.ui.ReadSlider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitReaderButton
import com.cosmotune.ui.orbit.OrbitReaderCard
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlin.math.roundToInt
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.rememberUiCoroutineScope
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The book reader (board D, the phone): the document on its reading theme with nothing over it until the small round tools
 * button at its foot is pressed; then the slim card in the book's colour: back to the library, the mono "CHAPTER 3 OF 12 · 35 %",
 * the page line with its moon (tap or drag to go to a chapter), and the round buttons (previous and next chapter, type, theme,
 * contents, bookmark and more: search, bookmarks, hide). Contents, bookmarks and the typography live in sheets.
 */
@Composable
fun BookReader(repository: ReadingRepository, document: ReadingDocument, onBack: () -> Unit) {
    val state by repository.state.collectAsUiState()
    val scope = rememberUiCoroutineScope()
    val book = document.book
    var locator by remember(book.id) { mutableStateOf(state.progress[book.id]?.locator ?: ReadingLocator()) }
    var preferences by remember(book.id) { mutableStateOf(state.preferences) }
    var panel by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    fun saveLocation(value: ReadingLocator) {
        locator = value
        scope.launch {
            try { repository.saveLocator(book.id, value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "The reading position could not be saved. Check available storage." }
        }
    }
    fun updatePreferences(value: ReadingPreferences) {
        preferences = value
        scope.launch {
            try { repository.updatePreferences(value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "Reader settings could not be saved." }
        }
    }
    fun bookmarkHere(epub: ReadingDocument.Epub?, section: Int) {
        scope.launch {
            try { repository.saveBookmark(book.id, if (epub != null) "Chapter ${section + 1} · ${(locator.progression * 100).toInt()}%" else "Page ${locator.page + 1}", locator) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "The bookmark could not be saved." }
        }
    }
    val epub = document as? ReadingDocument.Epub
    val section = epub?.spine?.indexOfFirst { it.href.substringBefore('#') == locator.resourceHref?.substringBefore('#') }?.takeIf { it >= 0 } ?: 0
    val look = orbitLook()
    val (fill, ink) = coverFill(null, book.id)
    MangaBackHandler(true, onBack)
    Box(Modifier.fillMaxSize().background(look.ground).testTag("books:reader")) {
        Column(Modifier.fillMaxSize()) {
            error?.let { message -> ReadInlineBanner(message, Modifier.windowInsetsPadding(WindowInsets.statusBars), onDismiss = { error = null }) }
            ReadingDocumentView(document, locator, preferences, search, onLocatorChanged = ::saveLocation,
                onError = { error = it }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("books:document"))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 620.dp).windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searching) Column(Modifier.fillMaxWidth().clip(ReadShape.md).background(look.ground).padding(vertical = 6.dp)) {
                ReadSearchField(search, { search = it }, if (epub != null) "Find in this chapter" else "Find in this document", Modifier.testTag("books:find-text"))
                Text(if (epub != null) "Matches are highlighted in this chapter." else "Find matching pages on desktop and Android 15+. Earlier Android versions support EPUB search.",
                    style = ReadType.caption(), color = look.dim, modifier = Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 4.dp))
            }
            if (!tools) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OrbitReaderButton(CosmoIcons.MoreVertical, "Show reading tools", ink, fill, { tools = true }, Modifier.background(fill, CircleShape).testTag("books:show-tools"))
            } else OrbitReaderCard(
                label = bookReaderLabel(epub, section, locator),
                progress = { if (epub != null && epub.spine.size > 1) section / (epub.spine.size - 1f) else locator.progression.toFloat().coerceIn(0f, 1f) },
                fill = fill, ink = ink,
                onSeek = if (epub != null && epub.spine.size > 1) ({ f -> epub.spine.getOrNull((f * (epub.spine.size - 1)).roundToInt())?.let { saveLocation(ReadingLocator(resourceHref = it.href)) } }) else null,
                leading = { OrbitReaderButton(CosmoIcons.ChevronLeft, "Library", ink, fill, onBack, Modifier.testTag("books:reader-back")) },
                modifier = Modifier.testTag("books:reader-card"),
            ) {
                if (epub != null) {
                    OrbitReaderButton(Icons.Rounded.SkipPrevious, "Previous", ink, fill, { if (section > 0) saveLocation(ReadingLocator(resourceHref = epub.spine[section - 1].href)) },
                        Modifier.testTag("books:previous-section"))
                    OrbitReaderButton(Icons.Rounded.SkipNext, "Next", ink, fill, { if (section + 1 < epub.spine.size) saveLocation(ReadingLocator(resourceHref = epub.spine[section + 1].href)) },
                        Modifier.testTag("books:next-section"))
                }
                Spacer(Modifier.weight(1f))
                OrbitReaderButton(Icons.Rounded.FormatSize, "Appearance", ink, fill, { panel = "Appearance" }, Modifier.testTag("books:appearance"))
                OrbitReaderButton(Icons.Outlined.DarkMode, "Page theme: " + preferences.theme.name.lowercase(), ink, fill, {
                    updatePreferences(preferences.copy(theme = ReadingTheme.entries[(preferences.theme.ordinal + 1) % ReadingTheme.entries.size]))
                }, Modifier.testTag("books:theme"))
                if (epub != null) OrbitReaderButton(CosmoIcons.List, "Contents", ink, fill, { panel = "Contents" }, Modifier.testTag("books:contents"))
                OrbitReaderButton(Icons.Outlined.BookmarkBorder, "Bookmark this position", ink, fill, { bookmarkHere(epub, section) })
                Box {
                    OrbitReaderButton(CosmoIcons.MoreVertical, "Tools", ink, fill, { more = true }, Modifier.testTag("books:reader-menu"))
                    OrbitMenu(more, { more = false }, listOf(
                        OrbitMenuItem("Search", icon = CosmoIcons.Search, selected = searching) { searching = !searching; if (!searching) search = "" },
                        OrbitMenuItem("Bookmarks", icon = Icons.Outlined.BookmarkBorder) { panel = "Bookmarks" },
                        OrbitMenuItem("Hide tools", icon = CosmoIcons.Close) { tools = false },
                    ), orbitFonts(), look, header = book.title)
                }
            }
        }
    }
    when (panel) {
        "Contents" -> KitSheet(onDismiss = { panel = "" }) {
            ReadSheetHeader("Contents", onDone = { panel = "" }, doneLabel = "Close")
            val navigation = epub?.navigation.orEmpty().flatNavigation()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                if (navigation.isNotEmpty()) items(navigation, key = { it.href + it.title }) { item ->
                    ReadSheetRow(item.title.ifBlank { "Untitled chapter" }, null, { saveLocation(ReadingLocator(resourceHref = item.href)); panel = "" })
                } else items(epub?.spine.orEmpty(), key = { it.href }) { item ->
                    ReadSheetRow(item.title.ifBlank { "Chapter ${(epub?.spine?.indexOf(item) ?: 0) + 1}" }, null, { saveLocation(ReadingLocator(resourceHref = item.href)); panel = "" })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        "Bookmarks" -> KitSheet(onDismiss = { panel = "" }) {
            ReadSheetHeader("Bookmarks", onDone = { panel = "" }, doneLabel = "Close")
            val bookmarks = state.bookmarks.filter { it.bookId == book.id }
            Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                ReadButton("Bookmark this position", { bookmarkHere(epub, section) }, kind = ReadKind.TONAL, icon = Icons.Outlined.BookmarkBorder, modifier = Modifier.testTag("books:add-bookmark"))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (bookmarks.isEmpty()) item { Text("Save a passage to return to it later.", style = ReadType.body(), color = orbitLook().dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                items(bookmarks, key = { it.id }) { bookmark ->
                    ReadSheetRow(bookmark.name, Icons.Outlined.BookmarkBorder, { saveLocation(bookmark.locator); panel = "" }, trailing = {
                        ReadIconButton(CosmoIcons.Close, "Remove bookmark", { scope.launch { try { repository.removeBookmark(bookmark.id) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = "The bookmark could not be removed." }
                        } })
                    })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        "Appearance" -> ReaderAppearance(preferences, document is ReadingDocument.Epub, onChange = ::updatePreferences, onDismiss = { panel = "" })
    }
}

private fun List<ReadingNavigation>.flatNavigation(): List<ReadingNavigation> = flatMap { listOf(it) + it.children.flatNavigation() }

/** The typography sheet (Kavita's book settings): page theme, typeface, size, line spacing, margins, and scroll or pages. */
@Composable
private fun ReaderAppearance(preferences: ReadingPreferences, reflowable: Boolean, onChange: (ReadingPreferences) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Reading appearance", onDone = onDismiss)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            item {
                ReadSheetSection("Page theme") {
                    ReadChipRow(inset = 0.dp) {
                        ReadingTheme.entries.forEach { theme -> ReadChip(theme.name.lowercase().replaceFirstChar(Char::uppercase), preferences.theme == theme, { onChange(preferences.copy(theme = theme)) }) }
                    }
                }
            }
            if (reflowable) {
                item {
                    ReadSheetSection("Typeface") {
                        ReadChipRow(inset = 0.dp) {
                            listOf("serif" to "Serif", "sans-serif" to "Sans serif", "monospace" to "Monospace").forEach { (value, label) -> ReadChip(label, preferences.fontFamily == value, { onChange(preferences.copy(fontFamily = value)) }) }
                        }
                    }
                }
                item {
                    ReadSheetSection("Text size · ${(preferences.fontScale * 100).toInt()}%") {
                        ReadSlider(preferences.fontScale, { onChange(preferences.copy(fontScale = it)) }, .75f..2.5f, 6, "Text size", Modifier.testTag("books:font-size"))
                    }
                }
                item {
                    ReadSheetSection("Line spacing · ${"%.1f".format(preferences.lineHeight)}") {
                        ReadSlider(preferences.lineHeight, { onChange(preferences.copy(lineHeight = it)) }, 1.1f..2.2f, 10, "Line spacing")
                    }
                }
                item {
                    ReadSheetSection("Margins · ${preferences.marginDp}") {
                        ReadSlider(preferences.marginDp.toFloat(), { onChange(preferences.copy(marginDp = it.toInt())) }, 8f..64f, 6, "Margins")
                    }
                }
                item {
                    ReadSheetSection("Reading direction") {
                        ReadChipRow(inset = 0.dp) {
                            ReadChip("Scroll", preferences.scrollMode, { onChange(preferences.copy(scrollMode = true)) })
                            ReadChip("Pages", !preferences.scrollMode, { onChange(preferences.copy(scrollMode = false)) })
                        }
                    }
                }
            } else item {
                Text("PDFs keep the publisher's layout. Use the page and zoom controls of the document.", style = ReadType.body(), color = Color(palette.textSecondary),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** "Chapter 3 of 12 · 35 %" for a book with chapters, "Page 6 · 35 %" for a PDF. */
internal fun bookReaderLabel(epub: ReadingDocument.Epub?, section: Int, locator: ReadingLocator): String {
    val percent = "${(locator.progression * 100).toInt()} %"
    return if (epub != null) "Chapter ${section + 1} of ${epub.spine.size} · " + (epub.spine.getOrNull(section)?.title?.takeIf { it.isNotBlank() } ?: percent)
    else "Page ${locator.page + 1} · $percent"
}
