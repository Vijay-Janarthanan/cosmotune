package com.cosmotune.reading.ui

import com.cosmotune.manga.ui.ReadSlider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.EditNote
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
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun saveLocation(value: ReadingLocator) {
        locator = value
        lastMove = System.currentTimeMillis()
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
    val page = remember(document) { ReadingPageControl() }
    var selection by remember(document) { mutableStateOf<ReadingSelection?>(null) }
    var editing by remember(document) { mutableStateOf<String?>(null) }
    var noting by remember(document) { mutableStateOf<ReadingSelection?>(null) }
    var reveal by remember(document) { mutableStateOf<String?>(null) }
    var lookingUp by remember(document) { mutableStateOf<String?>(null) }
    val speech = if (epub != null) rememberReadingSpeech() else null
    val latestPreferences by rememberUpdatedState(preferences)
    val aloud = remember(document, speech) { if (speech == null || epub == null) null else
        ReadAloud(speech, page, scope, epub, { latestPreferences }, { saveLocation(it) }, { error = it }) }
    DisposableEffect(aloud) { onDispose { aloud?.pause() } }
    val marks = remember(state.highlights, book.id) { ReadingNotesExport.highlights(state, book.id) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val save = rememberReadingSave { error = it }
    // The chapter's highlights are laid on whenever it loads or they change; a highlight chosen from the list is then scrolled to.
    LaunchedEffect(page, page.loads, marks) {
        val href = page.href ?: return@LaunchedEffect
        page.run(ReadingEpubScripts.highlights(marks.filter { it.href == href }))
        reveal?.let { id -> if (marks.find { it.id == id }?.href == href) { page.run(ReadingEpubScripts.reveal(id)); reveal = null } }
    }
    // The page has no way to call the app, so the reader looks at its selection twice a second.
    if (epub != null) LaunchedEffect(page) {
        while (true) {
            kotlinx.coroutines.delay(450)
            val (now, tapped) = parseSelection(page.run(ReadingEpubScripts.selection))
            if (now != selection) selection = now
            if (tapped != null) editing = tapped
        }
    }
    fun highlight(words: ReadingSelection, style: ReadingMarkStyle, note: String = "") {
        val href = page.href ?: return
        val index = epub?.spine?.indexOfFirst { it.href == href }?.coerceAtLeast(0) ?: 0
        val chapter = epub?.spine?.getOrNull(index)?.title?.takeIf { it.isNotBlank() }
            ?: epub?.navigation?.flatNavigation()?.firstOrNull { it.href.substringBefore('#') == href }?.title.orEmpty()
        scope.launch {
            try {
                repository.saveHighlight(book.id, href, words.start, words.end, words.text, style, words.progression, note, index, chapter)
                page.run(ReadingEpubScripts.clearSelection); selection = null
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The highlight could not be saved." }
        }
    }
    fun export(markdown: Boolean) {
        val current = state.books.find { it.id == book.id } ?: book
        val text = if (markdown) ReadingNotesExport.markdown(current, state) else ReadingNotesExport.html(current, state)
        save(ReadingNotesExport.fileName(current, if (markdown) "md" else "html"), if (markdown) "text/markdown" else "text/html", text.toByteArray())
    }
    val addFont = rememberReadingImport(ReadingPick.FONT) { name, open -> scope.launch {
        try { val font = repository.importFont(name, open); updatePreferences(preferences.copy(fontFamily = "font:${font.id}")) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "The font could not be added." }
    } }
    // KOReader sync: when another device is further on, say so (once) with a way there; this place goes back when the book closes.
    val spine = remember(document) { epub?.spine?.map { it.href }.orEmpty() }
    var remote by remember(book.id) { mutableStateOf<ReadingRemotePosition?>(null) }
    LaunchedEffect(book.id) { remote = repository.remotePosition(book.id, spine) }
    DisposableEffect(book.id) { onDispose { repository.pushProgressLater(book.id, spine) } }
    // Where in the whole book, for the foot line, time left and the reading statistics (chapters weighed by their size).
    val sizes = remember(document) { epub?.spine?.map { it.file.length() }.orEmpty() }
    val overall = if (epub != null) ReadingProgressMath.overall(locator, spine, sizes) else locator.progression
    val chapterEnd = if (epub != null) ReadingProgressMath.chapterEnd(locator, spine, sizes) else 1.0
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = System.currentTimeMillis() } }
    val latestOverall by rememberUpdatedState(overall)
    // Reading time: counted while the page moved in the last three minutes (or is being read aloud), saved each minute.
    LaunchedEffect(book.id) {
        var pending = 0L
        var from = latestOverall
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if (System.currentTimeMillis() - lastMove < 180_000 || aloud?.playing == true) pending += 15_000
            if (pending >= 60_000) {
                val to = latestOverall
                runCatching { repository.recordReading(book.id, pending, to - from) }
                from = to; pending = 0
            }
        }
    }
    val canSearch = epub != null || supportsReadingPdfSearch()
    val section = epub?.spine?.indexOfFirst { it.href.substringBefore('#') == locator.resourceHref?.substringBefore('#') }?.takeIf { it >= 0 } ?: 0
    val look = orbitLook()
    val (fill, ink) = coverFill(null, book.id)
    MangaBackHandler(true, onBack)
    ReaderKeepScreenOn(preferences.keepScreenOn)
    Box(Modifier.fillMaxSize().background(look.ground).testTag("books:reader")) {
        Column(Modifier.fillMaxSize()) {
            error?.let { message -> ReadInlineBanner(message, Modifier.windowInsetsPadding(WindowInsets.statusBars), onDismiss = { error = null }) }
            if (preferences.showTitle) Text(listOf(book.title, epub?.spine?.getOrNull(section)?.title.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").uppercase(),
                style = orbitFonts().label, color = look.dim.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 24.dp, vertical = 8.dp).testTag("books:title-line"))
            remote?.let { there ->
                ReadInlineBanner("${there.device} is further on, at ${(there.percentage * 100).toInt()} %.", Modifier.windowInsetsPadding(WindowInsets.statusBars).testTag("books:remote"),
                    actionLabel = "Go there", onAction = { saveLocation(there.locator); remote = null }, onDismiss = { remote = null })
            }
            ReadingDocumentView(document, locator, preferences, search, onLocatorChanged = ::saveLocation,
                onError = { error = it }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("books:document"), onCenterTap = { tools = !tools },
                control = page)
        }
        // Brightness is a dimming over the page (the system's own stays as it is); it never takes a tap.
        if (preferences.brightness < 1f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - preferences.brightness).coerceIn(0f, 0.7f))))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 620.dp).windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (aloud?.active == true) ReadAloudCard(aloud, fill, ink, onTimer = { panel = "Timer" }, onVoice = { panel = "Voice" })
            else selection?.let { words ->
                ReaderSelectionBar(words, fill, ink, onHighlight = { highlight(words, it) }, onNote = { noting = words }, extra = {
                    if (aloud != null) com.cosmotune.ui.orbit.OrbitReaderButton(androidx.compose.material.icons.Icons.Rounded.RecordVoiceOver, "Read aloud from here", ink, fill,
                        { scope.launch { page.run(ReadingEpubScripts.clearSelection); selection = null }; aloud.start(words.start) }, Modifier.testTag("books:selection:aloud"))
                },
                    onLookUp = { lookingUp = words.text }, onCopy = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(words.text))
                        scope.launch { page.run(ReadingEpubScripts.clearSelection); selection = null }
                    })
            }
            if (searching) Column(Modifier.fillMaxWidth().clip(ReadShape.md).background(look.ground).padding(vertical = 6.dp)) {
                ReadSearchField(search, { search = it }, if (epub != null) "Find in this chapter" else "Find in this document", Modifier.testTag("books:find-text"))
                Text(if (epub != null) "Matches are highlighted in this chapter." else "Find matching pages on desktop and Android 15+. Earlier Android versions support EPUB search.",
                    style = ReadType.caption(), color = look.dim, modifier = Modifier.padding(horizontal = ReadMargin + 4.dp, vertical = 4.dp))
            }
            // A tap on the middle of the page shows the tools where the page can tell (the phone); the desktop keeps the button.
            if (aloud?.active == true) Unit
            else if (!tools) Column(Modifier.fillMaxWidth()) {
                if (preferences.footerBar) com.cosmotune.ui.orbit.OrbitProgressLine({ overall.toFloat() }, look.dim, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), moon = 3.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, if (readingCenterTap) Alignment.CenterHorizontally else Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    val chapterTitle = if (epub != null) epub.spine.getOrNull(section)?.title?.takeIf { it.isNotBlank() } ?: "Chapter ${section + 1} of ${epub.spine.size}" else "Page ${locator.page + 1}"
                    val line = ReadingProgressMath.footer(preferences.footer, chapterTitle, overall, chapterEnd, state.paces[book.id], now)
                    if (line.isNotBlank() || readingCenterTap) Text(line.ifBlank { bookReaderLabel(epub, section, locator) }.uppercase(), style = orbitFonts().label,
                        color = look.dim.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("books:page-line"))
                    if (!readingCenterTap) OrbitReaderButton(CosmoIcons.MoreVertical, "Show reading tools", ink, fill, { tools = true }, Modifier.background(fill, CircleShape).testTag("books:show-tools"))
                }
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
                        OrbitMenuItem("Search", detail = if (canSearch) null else "PDF search requires Android 15 or later.",
                            icon = CosmoIcons.Search, selected = searching, enabled = canSearch) { searching = !searching; if (!searching) search = "" },
                        OrbitMenuItem("Bookmarks", icon = Icons.Outlined.BookmarkBorder) { panel = "Bookmarks" },
                        OrbitMenuItem("Dictionary", icon = CosmoIcons.Translate) { lookingUp = "" },
                        OrbitMenuItem("Read aloud", detail = if (aloud == null) "Reading aloud works in reflowing books, not PDFs." else null,
                            icon = androidx.compose.material.icons.Icons.Rounded.RecordVoiceOver, enabled = aloud != null) { aloud?.start() },
                        OrbitMenuItem("Highlights and notes", detail = if (epub == null) "Highlights work in reflowing books, not PDFs." else marks.size.takeIf { it > 0 }?.toString(),
                            icon = androidx.compose.material.icons.Icons.Rounded.EditNote, enabled = epub != null) { panel = "Highlights" },
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
                if (navigation.isNotEmpty()) items(navigation) { item ->
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
        "Highlights" -> HighlightsPanel(marks, onOpen = { mark ->
                panel = ""; reveal = mark.id
                if (page.href == mark.href) scope.launch { page.run(ReadingEpubScripts.reveal(mark.id)); reveal = null }
                else saveLocation(ReadingLocator(resourceHref = mark.href, progression = mark.progression))
            }, onRemove = { mark -> scope.launch {
                try { repository.removeHighlight(mark.id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = "The highlight could not be removed." }
            } }, onExport = ::export, onDismiss = { panel = "" })
        "Timer" -> aloud?.let { SleepTimerSheet(it, onDismiss = { panel = "" }) }
        "Voice" -> speech?.let { VoiceSheet(it, preferences, book.language, onChange = ::updatePreferences, onDismiss = { panel = "" }) }
        "Appearance" -> ReaderAppearance(preferences, document is ReadingDocument.Epub, onChange = ::updatePreferences, onDismiss = { panel = "" },
            fonts = state.fonts, onAddFont = addFont)
    }
    fun guarded(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "The highlight could not be changed." }
    }
    marks.find { it.id == editing }?.let { mark ->
        HighlightSheet(mark, onStyle = { style -> guarded { repository.updateHighlight(mark.id, style = style) } },
            onNote = { note -> guarded { repository.updateHighlight(mark.id, note = note) } },
            onRemove = { guarded { repository.removeHighlight(mark.id) } }, onDismiss = { editing = null })
    }
    lookingUp?.let { words -> LookupSheet(repository, words, book.language, onDismiss = { lookingUp = null }) }
    noting?.let { words -> NoteSheet(words.text, onSave = { note -> highlight(words, ReadingMarkStyle.YELLOW, note) }, onDismiss = { noting = null }) }
}

internal fun List<ReadingNavigation>.flatNavigation(): List<ReadingNavigation> = flatMap { listOf(it) + it.children.flatNavigation() }

/**
 * The appearance sheet (board frame 9): page colour, typeface, size, line spacing, margins, pages or scroll, then brightness and
 * keeping the screen on. The same choices are Settings › Read's defaults.
 */
@Composable
private fun ReaderAppearance(preferences: ReadingPreferences, reflowable: Boolean, onChange: (ReadingPreferences) -> Unit, onDismiss: () -> Unit,
    fonts: List<ReadingFont>, onAddFont: () -> Unit) {
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Appearance", onDone = onDismiss)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) { item { ReadingAppearanceChoices(preferences, reflowable, onChange, fonts, onAddFont) } }
    }
}

/** Ready pairs of page and text colour for "Your colours". */
internal val readingColourPairs = listOf("#fdf6e3" to "#2b2b2b", "#e8f1e4" to "#22351f", "#efeaf6" to "#2e2540", "#1c2633" to "#d8e1ea", "#2a2420" to "#e8d9c4", "#000000" to "#a8d8a0")

/**
 * Page colour (with your own colours), typeface (the book's own, the reader's added fonts), size, spacing, margins, paragraphs
 * (space after, indent, justify, hyphenate), layout (pages in one or two columns, or scroll), brightness and keep screen on.
 */
@Composable
internal fun ReadingAppearanceChoices(preferences: ReadingPreferences, reflowable: Boolean, onChange: (ReadingPreferences) -> Unit,
    fonts: List<ReadingFont> = emptyList(), onAddFont: (() -> Unit)? = null, onRemoveFont: ((String) -> Unit)? = null) {
    val palette = LocalCosmoColors.current.palette
    Column(Modifier.fillMaxWidth()) {
        ReadSheetSection("Page theme") {
            ReadChipRow(inset = 0.dp) {
                ReadingTheme.entries.forEach { theme -> ReadChip(theme.label, preferences.theme == theme, { onChange(preferences.copy(theme = theme)) }, Modifier.testTag("books:theme:${theme.name.lowercase()}")) }
            }
        }
        if (preferences.theme == ReadingTheme.CUSTOM) ReadSheetSection("Your colours") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                readingColourPairs.forEach { (page, text) ->
                    val selected = preferences.pageColor.equals(page, true) && preferences.textColor.equals(text, true)
                    Box(Modifier.size(40.dp).clip(CircleShape).background(hexColour(page))
                        .then(if (selected) Modifier.border(2.dp, orbitLook().accent, CircleShape) else Modifier.border(1.dp, orbitLook().dim.copy(alpha = 0.4f), CircleShape))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Use these colours") { onChange(preferences.copy(pageColor = page, textColor = text)) },
                        contentAlignment = Alignment.Center) { Text("Aa", style = ReadType.body(), color = hexColour(text)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HexField("Page", preferences.pageColor, { onChange(preferences.copy(pageColor = it)) }, Modifier.weight(1f))
                HexField("Text", preferences.textColor, { onChange(preferences.copy(textColor = it)) }, Modifier.weight(1f))
            }
        }
        if (reflowable) {
            ReadSheetSection("Typeface") {
                ReadChipRow(inset = 0.dp) {
                    listOf("publisher" to "The book's own", "serif" to "Serif", "sans-serif" to "Sans serif", "monospace" to "Monospace").forEach { (value, label) ->
                        ReadChip(label, preferences.fontFamily == value, { onChange(preferences.copy(fontFamily = value)) }, Modifier.testTag("books:font:$value"))
                    }
                    fonts.forEach { font -> ReadChip(font.name, preferences.fontFamily == "font:${font.id}", { onChange(preferences.copy(fontFamily = "font:${font.id}")) }) }
                    if (onAddFont != null) ReadChip("Add a font", false, onAddFont, Modifier.testTag("books:add-font"), icon = CosmoIcons.Plus)
                }
                if (fonts.isEmpty() && onAddFont != null) Text("Add a font file, such as OpenDyslexic, to read in it.", style = ReadType.caption(), color = orbitLook().dim,
                    modifier = Modifier.padding(top = 8.dp))
                if (onRemoveFont != null && fonts.isNotEmpty()) ReadChipRow(Modifier.padding(top = 8.dp), inset = 0.dp) {
                    fonts.forEach { font -> ReadChip("Remove ${font.name}", false, { onRemoveFont(font.id) }, icon = CosmoIcons.Close) }
                }
            }
            ReadSheetSection("Text size · ${(preferences.fontScale * 100).toInt()}%") {
                ReadSlider(preferences.fontScale, { onChange(preferences.copy(fontScale = it)) }, .75f..2.5f, 6, "Text size", Modifier.testTag("books:font-size"))
            }
            ReadSheetSection("Line spacing · ${"%.1f".format(preferences.lineHeight)}") {
                ReadSlider(preferences.lineHeight, { onChange(preferences.copy(lineHeight = it)) }, 1.1f..2.2f, 10, "Line spacing")
            }
            ReadSheetSection("Margins · ${preferences.marginDp}") {
                ReadSlider(preferences.marginDp.toFloat(), { onChange(preferences.copy(marginDp = it.toInt())) }, 8f..64f, 6, "Margins")
            }
            ReadSheetSection("Paragraphs") {
                ReadChipRow(inset = 0.dp) {
                    listOf(-1f to "The book's spacing", 0f to "No space", 0.5f to "Small space", 1f to "Space", 1.6f to "Wide space").forEach { (value, label) ->
                        ReadChip(label, preferences.paragraphSpacing == value, { onChange(preferences.copy(paragraphSpacing = value)) }, Modifier.testTag("books:paragraph:$value"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                com.cosmotune.ui.orbit.OrbitSwitchRow("Indent first lines", preferences.paragraphIndent, { onChange(preferences.copy(paragraphIndent = it)) }, Modifier.padding(vertical = 2.dp),
                    switchModifier = Modifier.testTag("books:indent"))
                com.cosmotune.ui.orbit.OrbitSwitchRow("Justify text", preferences.justify, { onChange(preferences.copy(justify = it)) }, Modifier.padding(vertical = 2.dp),
                    switchModifier = Modifier.testTag("books:justify"))
                com.cosmotune.ui.orbit.OrbitSwitchRow("Hyphenate long words", preferences.hyphenate, { onChange(preferences.copy(hyphenate = it)) }, Modifier.padding(vertical = 2.dp),
                    switchModifier = Modifier.testTag("books:hyphenate"))
            }
            ReadSheetSection("Layout") {
                ReadChipRow(inset = 0.dp) {
                    ReadChip("Pages", !preferences.scrollMode && preferences.columns == 1, { onChange(preferences.copy(scrollMode = false, columns = 1)) })
                    ReadChip("Two columns", !preferences.scrollMode && preferences.columns == 2, { onChange(preferences.copy(scrollMode = false, columns = 2)) }, Modifier.testTag("books:columns"))
                    ReadChip("Scroll", preferences.scrollMode, { onChange(preferences.copy(scrollMode = true)) })
                }
            }
        } else {
            Text("PDFs keep the publisher's layout. Use the page and zoom controls of the document.", style = ReadType.body(), color = Color(palette.textSecondary),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (reflowable) ReadSheetSection("Foot line") {
            ReadChipRow(inset = 0.dp) {
                ReadingFooterItem.entries.forEach { item ->
                    val on = item in preferences.footer
                    ReadChip(item.label, on, { onChange(preferences.copy(footer = if (on) preferences.footer - item else preferences.footer + item)) }, Modifier.testTag("books:footer:${item.name.lowercase()}"))
                }
            }
            Spacer(Modifier.height(8.dp))
            com.cosmotune.ui.orbit.OrbitSwitchRow("Progress bar under the page", preferences.footerBar, { onChange(preferences.copy(footerBar = it)) }, Modifier.padding(vertical = 2.dp))
            com.cosmotune.ui.orbit.OrbitSwitchRow("Book and chapter over the page", preferences.showTitle, { onChange(preferences.copy(showTitle = it)) }, Modifier.padding(vertical = 2.dp))
            com.cosmotune.ui.orbit.OrbitSwitchRow("Hide picture captions", preferences.hideCaptions, { onChange(preferences.copy(hideCaptions = it)) }, Modifier.padding(vertical = 2.dp))
        }
        ReadSheetSection("Brightness · ${(preferences.brightness * 100).toInt()}%") {
            ReadSlider(preferences.brightness, { onChange(preferences.copy(brightness = it.coerceIn(0.3f, 1f))) }, 0.3f..1f, 6, "Brightness", Modifier.testTag("books:brightness"))
        }
        com.cosmotune.ui.orbit.OrbitSwitchRow("Keep screen on while reading", preferences.keepScreenOn, { onChange(preferences.copy(keepScreenOn = it)) },
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp), switchModifier = Modifier.testTag("books:keep-screen-on"))
        Spacer(Modifier.height(16.dp))
    }
}

/** "Chapter 3 of 12 · 35 %" for a book with chapters, "Page 6 · 35 %" for a PDF. */
internal fun bookReaderLabel(epub: ReadingDocument.Epub?, section: Int, locator: ReadingLocator): String {
    val percent = "${(locator.progression * 100).toInt()} %"
    return if (epub != null) "Chapter ${section + 1} of ${epub.spine.size} · " + (epub.spine.getOrNull(section)?.title?.takeIf { it.isNotBlank() } ?: percent)
    else "Page ${locator.page + 1} · $percent"
}

/** [value] (#rrggbb) as a colour; grey when it is not one. */
internal fun hexColour(value: String): Color = value.removePrefix("#").toLongOrNull(16)?.takeIf { value.length == 7 }?.let { Color(0xFF000000 or it) } ?: Color.Gray

/** A colour typed as #rrggbb: kept once it is a whole colour. */
@Composable
private fun HexField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value) }
    Column(modifier) {
        Text(label, style = ReadType.caption(), color = orbitLook().dim)
        Spacer(Modifier.height(8.dp))
        com.cosmotune.ui.orbit.OrbitInputField(text, { next -> text = next.take(7); if (next.matches(Regex("#[0-9a-fA-F]{6}"))) onChange(next.lowercase()) }, "#rrggbb",
            error = !text.matches(Regex("#[0-9a-fA-F]{6}")))
    }
}
